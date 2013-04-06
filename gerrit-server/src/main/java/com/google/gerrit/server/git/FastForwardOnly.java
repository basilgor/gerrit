// Copyright (C) 2012 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.git;

import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.AccountCvsCredentials;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.ChangeMessage;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.client.PatchSetApproval;
import com.google.gerrit.server.ChangeUtil;
import com.google.gwtorm.server.OrmException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FastForwardOnly extends SubmitStrategy {

  FastForwardOnly(final SubmitStrategy.Arguments args) {
    super(args);
  }

  @Override
  protected CodeReviewCommit _run(final CodeReviewCommit mergeTip,
      final List<CodeReviewCommit> toMerge) throws MergeException {
    final List<CodeReviewCommit> allPending = new ArrayList<CodeReviewCommit>(toMerge);

    args.mergeUtil.reduceToMinimalMerge(args.mergeSorter, toMerge);
    CodeReviewCommit newMergeTip =
        args.mergeUtil.getFirstFastForward(mergeTip, args.rw, toMerge);

    while (!toMerge.isEmpty()) {
      final CodeReviewCommit n = toMerge.remove(0);
      n.statusCode = CommitMergeStatus.NOT_FAST_FORWARD;
    }

    newMergeTip = pushToCvs(mergeTip, newMergeTip, allPending);

    final PatchSetApproval submitApproval =
        args.mergeUtil.markCleanMerges(args.rw, args.canMergeFlag, newMergeTip,
            args.alreadyAccepted);
    setRefLogIdent(submitApproval);

    return newMergeTip;
  }

  private CodeReviewCommit findReviewCommit(
      final List<CodeReviewCommit> commits, final ObjectId id) throws MissingObjectException {
    for (final CodeReviewCommit rc : commits) {
      if (rc.getId().equals(id))
        return rc;
    }
    throw new MissingObjectException(id, "commit");
  }

  private void addChangeMessage(final Change c, final String body) {
    final String uuid;
    Logger log = LoggerFactory.getLogger(FastForwardOnly.class);

    try {
      uuid = ChangeUtil.messageUUID(args.db);
    } catch (OrmException e) {
      return;
    }
    final ChangeMessage m =
        new ChangeMessage(new ChangeMessage.Key(c.getId(), uuid), null,
            c.currentPatchSetId());
    m.setMessage(body);

    try {
      args.db.changeMessages().insert(Collections.singleton(m));
    } catch (OrmException err) {
      log.warn("Cannot record merge failure message", err);
    }
  }

  private CodeReviewCommit pushToCvs(final CodeReviewCommit mergeTip,
      CodeReviewCommit newMergeTip, final List<CodeReviewCommit> allPending) {

    CodeReviewCommit lastMerged = mergeTip;
    String parent = mergeTip.getId().getName();
    String commit = newMergeTip.getId().getName();

    if (parent.equals(commit)) {
        return newMergeTip;
    }

    Logger log = LoggerFactory.getLogger(FastForwardOnly.class);

    for (final CodeReviewCommit rc : allPending) {
        log.info("all pending: " + rc.getName() + " patchsetid: " + rc.patchsetId.toString());
    }

    RevWalk rw = new RevWalk(args.repo);
    try {
      rw.markStart(rw.parseCommit(newMergeTip));
      rw.markUninteresting(rw.parseCommit(mergeTip));
      rw.sort(RevSort.TOPO);
      rw.sort(RevSort.REVERSE, true);
      RevCommit c;
      CodeReviewCommit rc;

      while ((c = rw.next()) != null) {
        log.info("pending commit: " + c.name());
        rc = findReviewCommit(allPending, c.getId());
        lastMerged = pushOneToCvs(lastMerged, rc);
      }
    } catch (MissingObjectException e1) {
      e1.printStackTrace();
    } catch (IncorrectObjectTypeException e1) {
      e1.printStackTrace();
    } catch (IOException e1) {
      e1.printStackTrace();
    }

    return lastMerged;
  }

  private CodeReviewCommit pushOneToCvs(final CodeReviewCommit mergeTip,
      CodeReviewCommit newMergeTip) {
    Logger log = LoggerFactory.getLogger(FastForwardOnly.class);

    String parent = mergeTip.getId().getName();
    String commit = newMergeTip.getId().getName();

    PatchSet.Id toPush = newMergeTip.patchsetId;
    PatchSetApproval psApproval = args.mergeUtil.getSubmitter(toPush);
    String ident = psApproval.getAccountId().toString();
    Account.Id submitterId = psApproval.getAccountId();
    String branch = args.destBranch.get();
    String project = args.destBranch.getParentKey().get();
    String repoPath = args.repo.getDirectory().getAbsolutePath();
    AccountCvsCredentials cvscred;
    Account account;

    try {
      cvscred = args.db.accountCvsCredentials().get(submitterId);
      account = args.db.accounts().get(submitterId);
      if (cvscred == null) {
        newMergeTip.statusCode = CommitMergeStatus.NO_CVS_CREDENTIALS;
        return mergeTip;
      }
    } catch (OrmException e) {
        newMergeTip.statusCode = CommitMergeStatus.NO_CVS_CREDENTIALS;
        return mergeTip;
    }

    String cvsUser = cvscred.getCvsUser();
    String cvsSshPrivateKey = cvscred.getSshPrivateKey();

    log.info("cvs committer, going to merge: " + commit + " into: " + branch);

    String[] argv = new String[11];
    argv[0] = "/home/dummy/tmp/runme";
    argv[1] = repoPath;
    argv[2] = project;
    argv[3] = cvsUser;
    argv[4] = cvsSshPrivateKey;
    argv[5] = branch;
    argv[6] = parent;
    argv[7] = commit;
    argv[8] = ident;
    argv[9] = account.getFullName();
    argv[10] = account.getPreferredEmail();

    addChangeMessage(newMergeTip.change, "going to merge ass cvs user: " + cvsUser);

    int rc = 0;
    try {
      String line;
      Process p = Runtime.getRuntime().exec(argv);
      BufferedReader input =
          new BufferedReader(new InputStreamReader(p.getInputStream()));
      while ((line = input.readLine()) != null) {
        log.info("cvs committer: " + line);
      }
      input.close();
      rc = p.waitFor();
    } catch (Exception err) {
      err.printStackTrace();
    }

    if (rc != 0) {

    }

    return newMergeTip;
  }

  @Override
  public boolean retryOnLockFailure() {
    return false;
  }

  public boolean dryRun(final CodeReviewCommit mergeTip,
      final CodeReviewCommit toMerge) throws MergeException {
    return args.mergeUtil.canFastForward(args.mergeSorter, mergeTip, args.rw,
        toMerge);
  }
}
