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

package com.google.gerrit.server.dashboard;

import com.google.common.collect.Maps;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.OutputFormat;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectControl;
import com.google.gerrit.server.project.ProjectState;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Inject;

import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.Map;
import java.util.Set;

/** List projects visible to the calling user. */
public class ListDashboards {
  private static final Logger log = LoggerFactory.getLogger(ListDashboards.class);
  private static String REFS_DASHBOARDS = "refs/meta/dashboards/";

  public static enum Level {
    PROJECT
  };

  private final CurrentUser currentUser;
  private final ProjectCache projectCache;
  private final GitRepositoryManager repoManager;

  @Option(name = "--format", metaVar = "FMT", usage = "Output display format")
  private OutputFormat format = OutputFormat.JSON;

  private Level level;
  private String entityName;

  @Inject
  protected ListDashboards(CurrentUser currentUser, ProjectCache projectCache,
      GitRepositoryManager repoManager) {
    this.currentUser = currentUser;
    this.projectCache = projectCache;
    this.repoManager = repoManager;
  }

  public OutputFormat getFormat() {
    return format;
  }

  public ListDashboards setFormat(OutputFormat fmt) {
    if (!format.isJson()) {
      throw new IllegalArgumentException(format.name() + " not supported");
    }
    this.format = fmt;
    return this;
  }

  public ListDashboards setLevel(Level level) {
    this.level = level;
    return this;
  }

  public ListDashboards setEntityName(String entityName) {
    this.entityName = entityName;
    return this;
  }

  public void display(OutputStream out) {
    final PrintWriter stdout;
    try {
      stdout = new PrintWriter(new BufferedWriter(new OutputStreamWriter(out, "UTF-8")));
    } catch (UnsupportedEncodingException e) {
      // Our encoding is required by the specifications for the runtime.
      throw new RuntimeException("JVM lacks UTF-8 encoding", e);
    }

    try {
      final Map<String, DashboardInfo> dashboards;
      if (level != null) {
        switch (level) {
          case PROJECT:
            dashboards = projectDashboards(new Project.NameKey(entityName));
            break;
          default:
            throw new IllegalStateException("unsupported dashboard level: " + level);
        }
      } else {
        dashboards = Maps.newTreeMap();
      }

      format.newGson().toJson(dashboards,
          new TypeToken<Map<String, DashboardInfo>>() {}.getType(), stdout);
      stdout.print('\n');
    } finally {
      stdout.flush();
    }
  }

  private Map<String, DashboardInfo> projectDashboards(final Project.NameKey projectName) {
    final Map<String, DashboardInfo> dashboards = Maps.newTreeMap();

    final ProjectState projectState = projectCache.get(projectName);
    final ProjectControl projectControl = projectState.controlFor(currentUser);
    if (projectState == null || !projectControl.isVisible()) {
      return dashboards;
    }

    Repository repo = null;
    RevWalk revWalk = null;
    try {
      repo = repoManager.openRepository(projectName);
      revWalk = new RevWalk(repo);
      final Map<String, Ref> refs = repo.getRefDatabase().getRefs(REFS_DASHBOARDS);
      for (final Ref ref : refs.values()) {
        if (projectControl.controlForRef(ref.getName()).canRead()) {
          dashboards.putAll(loadDashboards(projectName, repo, revWalk, ref));
        }
      }
    } catch (IOException e) {
      log.warn("Failed to load dashboards of project " + projectName.get(), e);
    } finally {
      if (revWalk != null) {
        revWalk.release();
      }
      if (repo != null) {
        repo.close();
      }
    }

    return dashboards;
  }

  private Map<String, DashboardInfo> loadDashboards(
      final Project.NameKey projectName, final Repository repo,
      final RevWalk revWalk, final Ref ref) {
    final Map<String, DashboardInfo> dashboards = Maps.newTreeMap();
    TreeWalk treeWalk = new TreeWalk(repo);
    try {
      final RevCommit commit = revWalk.parseCommit(ref.getObjectId());
      final RevTree tree = commit.getTree();
      treeWalk.addTree(tree);
      treeWalk.setRecursive(true);
      while (treeWalk.next()) {
        DashboardInfo info = new DashboardInfo();
        info.dashboardName = treeWalk.getPathString();
        info.refName = ref.getName();
        info.projectName = projectName.get();
        info.id = createId(info.refName, info.dashboardName);
        final ObjectLoader loader = repo.open(treeWalk.getObjectId(0));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        loader.copyTo(out);
        Config dashboardConfig = new Config();
        dashboardConfig.fromText(new String(out.toByteArray(), "UTF-8"));

        info.description = dashboardConfig.getString("main", null, "description");

        final StringBuilder query = new StringBuilder();
        query.append("title=");
        query.append(info.dashboardName.replaceAll(" ", "+"));
        final Set<String> sections = dashboardConfig.getSubsections("section");
        for (final String section : sections) {
          query.append("&");
          query.append(section.replaceAll(" ", "+"));
          query.append("=");
          query.append(dashboardConfig.getString("section", section, "query"));
        }
        info.parameters = query.toString();

        dashboards.put(info.id, info);
      }
    } catch (IOException e) {
      log.warn("Failed to load dashboards of project " + projectName.get()
          + " from ref " + ref.getName(), e);
    } catch (ConfigInvalidException e) {
      log.warn("Failed to load dashboards of project " + projectName.get()
          + " from ref " + ref.getName(), e);
    } finally {
      treeWalk.release();
    }
    return dashboards;
  }

  private static String createId(final String refName,
      final String dashboardName) {
    return refName + ":" + dashboardName;
  }

  @SuppressWarnings("unused")
  private static class DashboardInfo {
    final String kind = "gerritcodereview#dashboard";
    String id;
    String dashboardName;
    String refName;
    String projectName;
    String description;
    String parameters;
  }
}