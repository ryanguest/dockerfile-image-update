/*
 * Copyright (c) 2017, salesforce.com, inc.
 * All rights reserved.
 * Licensed under the BSD 3-Clause license.
 * For full license text, see LICENSE.txt file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.dockerfileimageupdate.subcommands.impl;

import com.salesforce.dockerfileimageupdate.SubCommand;
import com.salesforce.dockerfileimageupdate.subcommands.ExecutableWithNamespace;
import com.salesforce.dockerfileimageupdate.utils.Constants;
import com.salesforce.dockerfileimageupdate.utils.DockerfileGitHubUtil;
import net.sourceforge.argparse4j.inf.Namespace;
import org.kohsuke.github.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SubCommand(help="updates all repositories' Dockerfiles with given base image",
        requiredParams = {Constants.IMG, Constants.TAG, Constants.STORE})

public class Parent implements ExecutableWithNamespace {
    private final static Logger log = LoggerFactory.getLogger(Parent.class);

    private DockerfileGitHubUtil dockerfileGitHubUtil;

    @Override
    public void execute(final Namespace ns, DockerfileGitHubUtil dockerfileGitHubUtil)
            throws IOException, InterruptedException {
        loadDockerfileGithubUtil(dockerfileGitHubUtil);
        Map<String, String> parentToPath = new HashMap<>();
        String img = ns.get(Constants.IMG);
        String tag = ns.get(Constants.TAG);

        log.info("Updating store...");
        this.dockerfileGitHubUtil.updateStore(ns.get(Constants.STORE), img, tag);

        log.info("Finding Dockerfiles with the given image...");
        PagedSearchIterable<GHContent> contentsWithImage = getGHContents(ns.get("o"), img);
        if (contentsWithImage == null) return;

        forkRepositoriesFound(parentToPath, contentsWithImage);

        GHMyself currentUser = this.dockerfileGitHubUtil.getMyself();
        if (currentUser == null) {
            throw new IOException("Could not retrieve authenticated user.");
        }

        PagedIterable<GHRepository> listOfRepos = dockerfileGitHubUtil.getGHRepositories(parentToPath, currentUser);

        String message = ns.get("m");
        List<IOException> exceptions = new ArrayList<>();
        for (GHRepository initialRepo : listOfRepos) {
            try {
                changeDockerfiles(ns, parentToPath, initialRepo, message);
            } catch (IOException e) {
                exceptions.add(e);
            }
        }
        if (exceptions.size() > 0) {
            log.info("There were {} errors with changing Dockerfiles.", exceptions.size());
            throw exceptions.get(0);
        }
    }

    protected void loadDockerfileGithubUtil(DockerfileGitHubUtil _dockerfileGitHubUtil) {
        dockerfileGitHubUtil = _dockerfileGitHubUtil;
    }

    protected PagedSearchIterable<GHContent> getGHContents(String org, String img)
            throws IOException, InterruptedException {
        PagedSearchIterable<GHContent> contentsWithImage = null;
        for (int i = 0; i < 5; i++) {
            contentsWithImage = dockerfileGitHubUtil.findFilesWithImage(img, org);
            if (contentsWithImage.getTotalCount() > 0) {
                break;
            } else {
                Thread.sleep(1000);
            }
        }

        int numOfContentsFound;
        numOfContentsFound = contentsWithImage.getTotalCount();
        if (numOfContentsFound <= 0) {
            log.info("Could not find any repositories with given image.");
            return null;
        }
        return contentsWithImage;
    }

    /* There is a separation here with forking and performing the Dockerfile update. This is because of the delay
     * on Github, where after the fork, there may be a time gap between repository creation and content replication
     * when forking. So, in hopes of alleviating the situation a little bit, we do all the forking before the
     * Dockerfile updates.
     */
    protected void forkRepositoriesFound(Map<String, String> parentToPath,
                                         PagedSearchIterable<GHContent> contentsWithImage) throws IOException {
        log.info("Forking repositories...");
        for (GHContent c : contentsWithImage) {
            /* Kohsuke's GitHub API library, when retrieving the forked repository, looks at the name of the parent to
             * retrieve. The issue with that is: GitHub, when forking two or more repositories with the same name,
             * automatically fixes the names to be unique (by appending "-#" to the end). Because of this edge case, we
             * cannot save the forks and iterate over the repositories; else, we end up missing/not updating the
             * repositories that were automatically fixed by GitHub. Instead, we save the names of the parent repos
             * in the map above, find the list of repositories under the authorized user, and iterate through that list.
             */
            GHRepository parent = c.getOwner();
            log.info("Forking {}...", parent.getFullName());
            parentToPath.put(c.getOwner().getFullName(), c.getPath());
            dockerfileGitHubUtil.checkFromParentAndFork(parent);
        }
    }

    protected void changeDockerfiles(Namespace ns, Map<String, String> parentToPath,
                                     GHRepository initialRepo, String message) throws IOException, InterruptedException {
        /* The Github API does not provide the parent if retrieved through a list. If we want to access its parent,
         * we need to retrieve it once again.
         */
        GHRepository retrievedRepo;
        if (initialRepo.isFork()) {
            log.info("Re-retrieving repo {}...", initialRepo.getFullName());
            try {
                retrievedRepo = dockerfileGitHubUtil.getRepo(initialRepo.getFullName());
            } catch (FileNotFoundException e) {
                /* The edge case here: If a different command calls getGHRepositories, and then this command calls
                 * it again within 60 seconds, it will still have the same list of repositories (because of caching).
                 * However, between the previous and current call, if some of those repositories are deleted, the call
                 * above may cause a FileNotFoundException. This clause prevents that exception from stopping our call;
                 * we do not need to stop because getGHRepositories checks that we have all the repositories we need.
                 *
                 * The integration test calls the testParent -> testAllCommand -> testIdempotency, and the
                 * testIdempotency was failing because of this edge condition.
                 */
                log.warn("This repository does not exist. The list of repositories must be outdated, but the list" +
                        "contains the repositories we need, so we ignore this error.");
                return;
            }
            log.info("Found repo {}.", retrievedRepo.getFullName());
        } else {
            return;
        }
        GHRepository parent = retrievedRepo.getParent();

        if (parent == null || !parentToPath.containsKey(parent.getFullName())) {
            return;
        }
        log.info("Fixing Dockerfiles in {}...", initialRepo.getFullName());
        String currBranch;
        String parentName = parent.getFullName();
        String branch = ns.get("b");
        if (branch == null) {
            currBranch = retrievedRepo.getDefaultBranch();
        } else {
            currBranch = branch;
        }
        GHContent content = dockerfileGitHubUtil.tryRetrievingContent(retrievedRepo, parentToPath.get(parentName),
                currBranch);
        dockerfileGitHubUtil.modifyOnGithub(content, currBranch, ns.get(Constants.IMG), ns.get(Constants.TAG), ns.get("c"));
        dockerfileGitHubUtil.createPullReq(parent, currBranch, retrievedRepo, message);
    }
}
