/*
 * Copyright (c) 2015 PocketHub
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.pockethub.android.ui.issue;

import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.ActionBar;
import android.support.v7.widget.Toolbar;
import android.view.Menu;
import android.view.MenuItem;

import com.meisolsson.githubsdk.core.ServiceGenerator;
import com.meisolsson.githubsdk.model.Issue;
import com.meisolsson.githubsdk.model.Repository;
import com.meisolsson.githubsdk.model.User;
import com.github.pockethub.android.Intents.Builder;
import com.github.pockethub.android.R;
import com.github.pockethub.android.core.issue.IssueStore;
import com.github.pockethub.android.core.issue.IssueUtils;
import com.github.pockethub.android.ui.FragmentProvider;
import com.github.pockethub.android.ui.PagerActivity;
import com.github.pockethub.android.ui.ViewPager;
import com.github.pockethub.android.ui.repo.RepositoryViewActivity;
import com.github.pockethub.android.ui.user.UriLauncherActivity;
import com.github.pockethub.android.util.AvatarLoader;
import com.github.pockethub.android.util.InfoUtils;
import com.meisolsson.githubsdk.service.repositories.RepositoryService;
import com.google.inject.Inject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP;
import static android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP;
import static com.github.pockethub.android.Intents.EXTRA_ISSUE_NUMBERS;
import static com.github.pockethub.android.Intents.EXTRA_POSITION;
import static com.github.pockethub.android.Intents.EXTRA_REPOSITORIES;
import static com.github.pockethub.android.Intents.EXTRA_REPOSITORY;

/**
 * Activity to display a collection of issues or pull requests in a pager
 */
public class IssuesViewActivity extends PagerActivity {

    private static final String EXTRA_PULL_REQUESTS = "pullRequests";

    /**
     * Create an intent to show a single issue
     *
     * @param issue
     * @return intent
     */
    public static Intent createIntent(final Issue issue) {
        return createIntent(Collections.singletonList(issue), 0);
    }

    /**
     * Create an intent to show issue
     *
     * @param issue
     * @param repository
     * @return intent
     */
    public static Intent createIntent(final Issue issue,
        final Repository repository) {
        return createIntent(Collections.singletonList(issue), repository, 0);
    }

    /**
     * Create an intent to show issues with an initial selected issue
     *
     * @param issues
     * @param repository
     * @param position
     * @return intent
     */
    public static Intent createIntent(final Collection<? extends Issue> issues,
        final Repository repository, final int position) {
        int[] numbers = new int[issues.size()];
        boolean[] pullRequests = new boolean[issues.size()];
        int index = 0;
        for (Issue issue : issues) {
            numbers[index] = issue.number();
            pullRequests[index] = IssueUtils.isPullRequest(issue);
            index++;
        }
        return new Builder("issues.VIEW").add(EXTRA_ISSUE_NUMBERS, numbers)
            .add(EXTRA_REPOSITORY, repository)
            .add(EXTRA_POSITION, position)
            .add(EXTRA_PULL_REQUESTS, pullRequests).toIntent();
    }

    /**
     * Create an intent to show issues with an initial selected issue
     *
     * @param issues
     * @param position
     * @return intent
     */
    public static Intent createIntent(Collection<? extends Issue> issues,
        int position) {
        final int count = issues.size();
        int[] numbers = new int[count];
        boolean[] pullRequests = new boolean[count];
        ArrayList<Repository> repos = new ArrayList<>(count);
        int index = 0;
        for (Issue issue : issues) {
            numbers[index] = issue.number();
            pullRequests[index] = IssueUtils.isPullRequest(issue);
            index++;

            Repository repoId = null;
            Repository issueRepo = issue.repository();
            if (issueRepo != null) {
                User owner = issueRepo.owner();
                if (owner != null) {
                    repoId = InfoUtils.createRepoFromData(owner.login(), issueRepo.name());
                }
            }
            if (repoId == null) {
                repoId = InfoUtils.createRepoFromUrl(issue.htmlUrl());
            }
            repos.add(repoId);
        }

        Builder builder = new Builder("issues.VIEW");
        builder.add(EXTRA_ISSUE_NUMBERS, numbers);
        builder.add(EXTRA_REPOSITORIES, repos);
        builder.add(EXTRA_POSITION, position);
        builder.add(EXTRA_PULL_REQUESTS, pullRequests);
        return builder.toIntent();
    }

    private ViewPager pager;

    private int[] issueNumbers;

    private boolean[] pullRequests;

    private ArrayList<Repository> repoIds;

    private Repository repo;

    @Inject
    private AvatarLoader avatars;

    @Inject
    private IssueStore store;

    private final AtomicReference<User> user = new AtomicReference<>();

    private boolean canWrite;

    private IssuesPagerAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        issueNumbers = getIntArrayExtra(EXTRA_ISSUE_NUMBERS);
        pullRequests = getBooleanArrayExtra(EXTRA_PULL_REQUESTS);
        repoIds = getIntent().getParcelableArrayListExtra(EXTRA_REPOSITORIES);
        repo = getParcelableExtra(EXTRA_REPOSITORY);

        setContentView(R.layout.activity_pager);

        setSupportActionBar((Toolbar) findViewById(R.id.toolbar));

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        if (repo != null) {
            ActionBar actionBar = getSupportActionBar();
            actionBar.setSubtitle(InfoUtils.createRepoId(repo));
            user.set(repo.owner());
            avatars.bind(actionBar, user);
        }

        // Load avatar if single issue and user is currently unset or missing
        // avatar URL
        if (repo == null) {
            Repository temp = repo != null ? repo : repoIds.get(0);
            ServiceGenerator.createService(this, RepositoryService.class)
                    .getRepository(temp.owner().login(), temp.name())
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .compose(this.bindToLifecycle())
                    .subscribe(response -> repositoryLoaded(response.body()));
        } else {
            repositoryLoaded(repo);
        }
    }

    private void repositoryLoaded(Repository repo) {
        if (issueNumbers.length == 1 && (user.get() == null || user.get().avatarUrl() == null)) {
            avatars.bind(getSupportActionBar(), repo.owner());
        }

        canWrite = repo.permissions() != null && (repo.permissions().admin() || repo.permissions().push());

        invalidateOptionsMenu();
        configurePager();
    }

    private void configurePager() {
        int initialPosition = getIntExtra(EXTRA_POSITION);
        pager = (ViewPager) findViewById(R.id.vp_pages);

        if (repo != null) {
            adapter = new IssuesPagerAdapter(this, repo, issueNumbers, canWrite);
        } else {
            adapter = new IssuesPagerAdapter(this, repoIds, issueNumbers, store, canWrite);
        }
        pager.setAdapter(adapter);

        pager.setOnPageChangeListener(this);
        pager.scheduleSetItem(initialPosition, this);
        onPageSelected(initialPosition);
    }

    private void updateTitle(final int position) {
        int number = issueNumbers[position];
        boolean pullRequest = pullRequests[position];

        if (pullRequest) {
            getSupportActionBar().setTitle(
                    getString(R.string.pull_request_title) + number);
        } else {
            getSupportActionBar().setTitle(
                    getString(R.string.issue_title) + number);
        }
    }

    @Override
    public void onPageSelected(final int position) {
        super.onPageSelected(position);

        if (repo != null) {
            updateTitle(position);
            return;
        }

        if (repoIds == null) {
            return;
        }

        ActionBar actionBar = getSupportActionBar();
        repo = repoIds.get(position);
        if (repo != null) {
            updateTitle(position);
            actionBar.setSubtitle(InfoUtils.createRepoId(repo));
            Issue issue = store.getIssue(repo, issueNumbers[position]);
            if (issue != null) {
                Repository fullRepo = issue.repository();
                if (fullRepo != null && fullRepo.owner() != null) {
                    user.set(fullRepo.owner());
                    avatars.bind(actionBar, user);
                } else {
                    actionBar.setLogo(null);
                }
            } else {
                actionBar.setLogo(null);
            }
        } else {
            actionBar.setSubtitle(null);
            actionBar.setLogo(null);
        }
    }

    @Override
    public void onDialogResult(int requestCode, int resultCode, Bundle arguments) {
        adapter.onDialogResult(pager.getCurrentItem(), requestCode, resultCode,
            arguments);
    }

    @Override
    public void startActivity(Intent intent) {
        Intent converted = UriLauncherActivity.convert(intent);
        if (converted != null) {
            super.startActivity(converted);
        } else {
            super.startActivity(intent);
        }
    }

    @Override
    protected FragmentProvider getProvider() {
        return adapter;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                Repository repository = repo;
                if (repository == null) {
                    int position = pager.getCurrentItem();
                    Repository repoId = repoIds.get(position);
                    if (repoId != null) {
                        Issue issue = store.getIssue(repoId,
                            issueNumbers[position]);
                        if (issue != null) {
                            repository = issue.repository();
                        }
                    }
                }
                if (repository != null) {
                    Intent intent = RepositoryViewActivity.createIntent(repository);
                    intent.addFlags(FLAG_ACTIVITY_CLEAR_TOP
                        | FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(intent);
                }
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }
}
