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

import com.google.gerrit.common.ChangeHookRunner.HookResult;
import com.google.gerrit.common.ChangeHooks;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.AccountCvsCredentials;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.ChangeMessage;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.client.PatchSetApproval;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.ChangeUtil;
import com.google.gwtorm.server.OrmException;
import com.google.gwtorm.server.ResultSet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FastForwardCVS extends SubmitStrategy {
  private final Pattern ticketPattern;
  private final ChangeHooks hooks;

  FastForwardCVS(final SubmitStrategy.Arguments args, final ChangeHooks hooks) {
    super(args);

    this.ticketPattern = Pattern.compile("TICKET:\\s?([0-9]+)");
    this.hooks = hooks;
  }

  @Override
  protected CodeReviewCommit _run(final CodeReviewCommit mergeTip,
      final List<CodeReviewCommit> toMerge) throws MergeException {
    final List<CodeReviewCommit> allPending =
        new ArrayList<CodeReviewCommit>(toMerge);

    args.mergeUtil.reduceToMinimalMerge(args.mergeSorter, toMerge);
    CodeReviewCommit newMergeTip =
        args.mergeUtil.getFirstFastForward(mergeTip, args.rw, toMerge);

    while (!toMerge.isEmpty()) {
      final CodeReviewCommit n = toMerge.remove(0);
      n.statusCode = CommitMergeStatus.NOT_FAST_FORWARD;
    }

    if (!hasBranchPrefix(args.destBranch.get())) {
      newMergeTip = pushToCvs(mergeTip, newMergeTip, allPending);
    }

    final PatchSetApproval submitApproval =
        args.mergeUtil.markCleanMerges(args.rw, args.canMergeFlag, newMergeTip,
            args.alreadyAccepted);
    setRefLogIdent(submitApproval);

    return newMergeTip;
  }

  private boolean hasBranchPrefix(final String fullRef) {
    final String refsPrefix = "refs/heads/";
    String branch;

    if (!fullRef.startsWith(refsPrefix))
        return true;

    branch = fullRef.substring(refsPrefix.length());
    if (branch.indexOf('/') != -1)
        return true;

    return false;
  }

  private CodeReviewCommit findReviewCommit(
      final List<CodeReviewCommit> commits, final ObjectId id)
      throws MissingObjectException {
    for (final CodeReviewCommit rc : commits) {
      if (rc.getId().equals(id)) return rc;
    }
    throw new MissingObjectException(id, "commit");
  }

  private void addChangeMessage(final Change c, final String body) {
    final String uuid;
    Logger log = LoggerFactory.getLogger(FastForwardCVS.class);

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
      args.db.commit();
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

    Logger log = LoggerFactory.getLogger(FastForwardCVS.class);

    for (final CodeReviewCommit rc : allPending) {
      log.info("all pending: " + rc.getName() + " patchsetid: "
          + rc.patchsetId.toString());
    }

    RevWalk rw = new RevWalk(args.repo);
    try {
      rw.markStart(rw.parseCommit(newMergeTip));
      rw.markUninteresting(rw.parseCommit(mergeTip));
      rw.sort(RevSort.TOPO);
      rw.sort(RevSort.REVERSE, true);
      RevCommit c;
      CodeReviewCommit rc;
      boolean fail = false;

      while ((c = rw.next()) != null) {
        log.info("pending commit: " + c.name());
        rc = findReviewCommit(allPending, c.getId());
        if (!fail) {
          lastMerged = pushOneToCvs(mergeTip, rc);
          if (lastMerged.getId().equals(mergeTip.getId())) {
            fail = true;
            // FIXME: change on cvs push failed
            // rc.statusCode = CommitMergeStatus.NOT_FAST_FORWARD;
          }
        } else {
          // TODO: what to do with left patches on failed merge?
          // rc.statusCode = CommitMergeStatus.MISSING_DEPENDENCY;
        }
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

  private final String matchTicket(final String msg) {
    Logger log = LoggerFactory.getLogger(FastForwardCVS.class);
    String ticket = null;
    Matcher match = ticketPattern.matcher(msg);
    if (match.find()) {
      ticket = match.group(1);
      log.info("matchTicket: '" + msg + "' matches: '" + ticket + "'");
    }
    //else {
    //  log.info("matchTicket: '" + msg + "' no match");
    //}
    return ticket;
  }

  private final String findTicket(final CodeReviewCommit c) {
    String ticket = null;
    String match;

    match = matchTicket(c.getFullMessage());
    if (match != null) ticket = match;

    try {
      ResultSet<ChangeMessage> messages =
          args.db.changeMessages().byChange(c.change.getId());
      for (ChangeMessage cm : messages) {
        match = matchTicket(cm.getMessage());
        if (match != null) ticket = match;
      }
    } catch (OrmException e) {
      e.printStackTrace();
    }
    return ticket;
  }

  private CodeReviewCommit pushOneToCvs(final CodeReviewCommit mergeTip,
      CodeReviewCommit newMergeTip) {
    Logger log = LoggerFactory.getLogger(FastForwardCVS.class);

    //String parent = mergeTip.getId().getName();
    String commit = newMergeTip.getId().getName();

    PatchSet.Id toPush = newMergeTip.patchsetId;
    String changeRefName = toPush.toRefName();
    PatchSetApproval psApproval = args.mergeUtil.getSubmitter(toPush);
    //String ident = psApproval.getAccountId().toString();
    Account.Id submitterId = psApproval.getAccountId();
    String branch = args.destBranch.get();
    Project.NameKey projNameKey = args.destBranch.getParentKey();
    //String project = args.destBranch.getParentKey().get();
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
    String ticket = findTicket(newMergeTip);
    if (ticket == null) {
      newMergeTip.statusCode = CommitMergeStatus.NO_TICKET;
      return mergeTip;
    }

    log.info("cvs committer, going to merge: " + commit + " into: " + branch);

    addChangeMessage(newMergeTip.change, "going to merge: " + commit + " to: "
        + ticket + " as cvs user: " + cvsUser);

    HookResult result =
        hooks.doCvsPushHook(projNameKey, repoPath, changeRefName, branch, ticket,
                account, cvsUser, cvsSshPrivateKey, mergeTip.getId(), newMergeTip.getId());

    if (result != null) {
      String message = result.toString().trim();

      if (result.getExitValue() != 0) {
        message += "\ncvs-push rc: " + Integer.toString(result.getExitValue());
        addChangeMessage(newMergeTip.change, message);
        newMergeTip.statusCode = CommitMergeStatus.CVS_PUSH_FAILED;
        return mergeTip;
      } else if (!message.isEmpty()) {
        addChangeMessage(newMergeTip.change, message);
      }
    } else {
      addChangeMessage(newMergeTip.change, "Could not run CVS push hook.");
      newMergeTip.statusCode = CommitMergeStatus.CVS_PUSH_FAILED;
      return mergeTip;
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
