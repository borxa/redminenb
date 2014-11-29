/*
 * Copyright 2012 Anchialas and Mykolaas.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.kenai.redminenb.repository;

import com.kenai.redminenb.Redmine;
import com.kenai.redminenb.RedmineConfig;
import com.kenai.redminenb.RedmineConnector;
import com.kenai.redminenb.issue.RedmineIssue;
import com.kenai.redminenb.query.RedmineQuery;
import com.kenai.redminenb.query.RedmineQueryController;
import com.kenai.redminenb.user.RedmineUser;
import com.kenai.redminenb.util.Is;

import com.kenai.redminenb.api.AuthMode;
import com.taskadapter.redmineapi.NotFoundException;
import com.taskadapter.redmineapi.RedmineException;
import com.taskadapter.redmineapi.RedmineManager;
import com.taskadapter.redmineapi.bean.Issue;
import com.taskadapter.redmineapi.bean.IssueCategory;
import com.taskadapter.redmineapi.bean.IssuePriority;
import com.taskadapter.redmineapi.bean.IssueStatus;
import com.taskadapter.redmineapi.bean.Membership;
import com.taskadapter.redmineapi.bean.Project;
import com.taskadapter.redmineapi.bean.TimeEntryActivity;
import com.taskadapter.redmineapi.bean.Tracker;
import com.taskadapter.redmineapi.bean.Version;
import java.awt.Image;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import org.apache.commons.lang.math.Fraction;
import org.netbeans.api.annotations.common.NonNull;
import org.netbeans.modules.bugtracking.spi.RepositoryController;
import org.netbeans.modules.bugtracking.spi.RepositoryInfo;
import org.netbeans.modules.bugtracking.spi.RepositoryProvider;
import org.openide.DialogDisplayer;
import org.openide.NotifyDescriptor;
import org.openide.util.Exceptions;
import org.openide.util.Lookup;
import org.openide.util.NbBundle;
import org.openide.util.RequestProcessor;
import org.openide.util.lookup.AbstractLookup;
import org.openide.util.lookup.InstanceContent;

/**
 * Redmine repository manager.
 *
 * @author Mykolas
 * @author Anchialas <anchialas@gmail.com>
 */
@NbBundle.Messages({
    "# {0} - repo name",
    "# {1} - user name",
    "# {2} - redmine url",
    "LBL_RepositoryTooltip=\"Redmine repository<br>{0} : {1}@{2}"
})
public class RedmineRepository {    
    static final String PROPERTY_AUTH_MODE = "authMode";                // NOI18N  
    static final String PROPERTY_ACCESS_KEY = "accessKey";              // NOI18N  
    static final String PROPERTY_PROJECT_ID = "projectId";              // NOI18N  
    static final String PROPERTY_FEATURE_WATCHERS = "featureWatchers";  // NOI18N
    
    private static final List<TimeEntryActivity> fallbackTimeActivityEntries;
    
    static {
        TimeEntryActivity design = new TimeEntryActivity();
        design.setDefault(false);
        design.setId(8);
        design.setName("Design");
        TimeEntryActivity development = new TimeEntryActivity();
        development.setDefault(false);
        development.setId(9);
        development.setName("Development");
        fallbackTimeActivityEntries = Collections.unmodifiableList(
                Arrays.asList(design, development));
    }
 
    private static final List<IssuePriority> fallbackIssuePriorities;
            
    static {
        fallbackIssuePriorities = Collections.unmodifiableList( Arrays.asList(
                createIssuePriority(7, "Immediate", false),
                createIssuePriority(6, "Urgent", false),
                createIssuePriority(5, "High", false),
                createIssuePriority(4, "Normal", true),
                createIssuePriority(3, "Low", false)));
    }
                    
    
    private RepositoryInfo info;
    private transient RepositoryController controller;
    private Map<String, RedmineQuery> queries = null;
    // TODO Create manager wrapping class to handle Redmine related errors
    private transient RedmineManager manager;
    private transient RedmineUser currentUser;
    private transient Project project;
    private transient Lookup lookup;
    private final transient InstanceContent ic;

    private final Set<String> issuesToRefresh = new HashSet<>(5);
    private final Set<RedmineQuery> queriesToRefresh = new HashSet<>(3);
    private RequestProcessor.Task refreshIssuesTask;
    private RequestProcessor.Task refreshQueryTask;
    private RequestProcessor refreshProcessor;

    private final IssueCache issueCache = new IssueCache(this);

    private final Set<RedmineIssue> newIssues = Collections.synchronizedSet(new HashSet<RedmineIssue>());
    private final Map<String, RedmineIssue> issues = Collections.synchronizedMap(new HashMap<String, RedmineIssue>());
    
    // Make sure we know all instances we created - a crude hack, but API does
    // not allow ourselfes ....
    private static final List<WeakReference<RedmineRepository>> repositoryList
            = Collections.synchronizedList(new LinkedList<WeakReference<RedmineRepository>>());

    {
        repositoryList.add(new WeakReference<>(this));
    }
    
    public static RedmineRepository getInstanceyById(@NonNull String id) {
        if( id == null ) {
            throw new NullPointerException("getInstanceById might not be called with null!");
        }
        synchronized(repositoryList) {
            Iterator<WeakReference<RedmineRepository>> it = repositoryList.iterator();
            RedmineRepository result = null;
            while(it.hasNext()) {
                WeakReference<RedmineRepository> weak = it.next();
                RedmineRepository hard = weak.get();
                if(hard == null) {
                    it.remove();
                } else {
                    if(id.equals(hard.getID()) && result == null) {
                        result = hard;
                    }
                }
            }
            return result;
        }
    }
    
    /**
     * Default constructor required for deserializing.
     */
    public RedmineRepository() {
        this.ic = new InstanceContent();
    }

    public RedmineRepository(RepositoryInfo info) { 
        this();
        this.info = info;
        try {
            String projectId = info.getValue(PROPERTY_PROJECT_ID);
            if (projectId != null) {
                setProject(getManager().getProjectByKey(projectId));
            }
        } catch (RedmineException ex) {
            Exceptions.printStackTrace(ex);
        }
    }

    public IssueCache getIssueCache() {
        return issueCache;
    }
 
    public Image getIcon() {
        return Redmine.getIconImage();
    }

    public boolean isReachable() throws IOException {
        URL url = new URL(getUrl());
        //URLConnection conn = url.openConnection();
        //return InetAddress.getByName(url.getHost()).isReachable(1000);
        // TODO InetAddress#isReachable does not work with some systems
        return true;
    }

    public RepositoryInfo getInfo() {
        return info;
    }

    synchronized void setInfoValues(String name, String url, String user, char[] password,
            String accessKey, AuthMode authMode, Project project, boolean featureWatchers) {
        String id = info != null ? info.getID() : name + System.currentTimeMillis();
        String httpUser = null;
        char[] httpPassword = null;
        RepositoryInfo ri = new RepositoryInfo(id,
                RedmineConnector.ID,
                url,
                name,
                getTooltip(name, user, url),
                user,
                httpUser,
                password,
                httpPassword);
        ri.putValue(PROPERTY_FEATURE_WATCHERS, Boolean.toString(featureWatchers));
        info = ri;
        setAccessKey(accessKey);
        setAuthMode(authMode);
        setProject(project);
    }

    public String getDisplayName() {
        if (info == null) {
            return "";
        }
        try {
            if (isReachable()) {
                return info.getDisplayName();
            }
        } catch (IOException ex) {
        }
        return info.getDisplayName() + " (offline)";
    }

    private String getTooltip(String repoName, String user, String url) {
        return Bundle.LBL_RepositoryTooltip(repoName, user, url);
    }

    public String getID() {
        return info.getID();
    }

    public String getUrl() {
        if(info == null) {
            return "";
        }
        return info.getUrl();
    }

    public AuthMode getAuthMode() {
        if(info == null) {
            return AuthMode.AccessKey;
        }
        return AuthMode.get(info.getValue(PROPERTY_AUTH_MODE));
    }

    public void setAuthMode(AuthMode authMode) {
        AuthMode old = getAuthMode();
        if (!Is.equals(old, authMode)) {
            manager = null;
        }
        info.putValue(PROPERTY_AUTH_MODE, authMode == null ? null : authMode.name());
    }

    public String getAccessKey() {
        if(info == null) {
            return "";
        }
        return info.getValue(PROPERTY_ACCESS_KEY);
    }

    public void setAccessKey(String accessKey) {
        String old = getAccessKey();
        if (!Is.equals(old, accessKey)) {
            manager = null; // force reconnect
        }
        info.putValue(PROPERTY_ACCESS_KEY, accessKey);
    }

    public char[] getPassword() {
        if(info == null) {
            return new char[0];
        }
        return info.getPassword();
    }

    public String getUsername() {
        if(info == null) {
            return "";
        }
        return info.getUsername();
    }

    public Project getProject() {
        return project;
    }

    public final void setProject(Project project) {
        this.project = project;
        info.putValue(PROPERTY_PROJECT_ID, project == null ? null : String.valueOf(project.getId()));
    }

    public RedmineIssue getIssue(String issueId) {
        RedmineIssue redmineIssue = null;
        if (issueId != null) {
            redmineIssue = issueCache.get(issueId);
            if (redmineIssue == null) {
                try {
                    Issue issue = getManager().getIssueById(Integer.valueOf(issueId));
                    redmineIssue = issueCache.cachedRedmineIssue(issue);
                } catch (NotFoundException ex) {
                    // do nothing
                } catch (Exception ex) {
                    Redmine.LOG.log(Level.SEVERE, null, ex);
                }
            }
        }
        return redmineIssue;
    }

    public Collection<RedmineIssue> getIssues(final String... ids) {
        final List<RedmineIssue> ret = new ArrayList<>(ids.length);
        for (String id : ids) {
            RedmineIssue issue = getIssue(id);
            if (issue != null) {
                ret.add(issue);
            }
        }
        return ret;
    }

    public void remove() {
    }

    synchronized void resetRepository(boolean keepConfiguration) {
        if (!keepConfiguration) {
            manager = null;
        }
    }

    public RepositoryController getController() {
        if (controller == null) {
            controller = new RedmineRepositoryController(this);
        }
        return controller;
    }

    public RedmineIssue createIssue(String summary, String description) {
        RedmineIssue issue = new RedmineIssue(this, summary, description);
        newIssues.add(issue);
        return issue;
    }

    public RedmineIssue createIssue() {
        RedmineIssue issue = new RedmineIssue(this);
        newIssues.add(issue);
        return issue;
    }
    
    boolean canAttachFiles() {
        return true;
    }

    public RedmineQuery createQuery() {
        return new RedmineQuery(this);
    }

    private synchronized Map<String, RedmineQuery> getQueryMap() {
        if (queries == null) {
            queries = Collections.synchronizedMap(new HashMap<String, RedmineQuery>());
            String[] qs = RedmineConfig.getInstance().getQueries(getID());
            for (String queryName : qs) {
                RedmineQuery q = RedmineConfig.getInstance().getQuery(this, queryName);
                if (q != null) {
                    queries.put(queryName, q);
                } else {
                    Redmine.LOG.log(Level.WARNING, "Couldn''t find query with stored name {0}", queryName); // NOI18N
                }
            }
        }
        return queries;
    }

    public void removeQuery(String displayName) {
        RedmineConfig.getInstance().removeQuery(this, displayName);
        getQueryMap().remove(displayName);
        fireQueryListChanged();

    }

    public void saveQuery(RedmineQuery query) {
        assert info != null;
        RedmineConfig.getInstance().putQuery(this, query);
        getQueryMap().put(query.getDisplayName(), query);
        fireQueryListChanged();
    }

    private void fireQueryListChanged() {
        Redmine.LOG.log(Level.FINER, "firing query list changed for repository {0}", new Object[]{getDisplayName()}); // NOI18N
        propertyChangeSupport.firePropertyChange(RepositoryProvider.EVENT_QUERY_LIST_CHANGED, null, null);
    }

    public Collection<RedmineQuery> getQueries() {
        return getQueryMap().values();
    }

    /**
     * Get this {@link #project}'s users.
     *
     * @return
     */
    public Collection<RedmineUser> getUsers() {
        List<RedmineUser> users = new ArrayList<>();
        try {
            users.add(currentUser);
            for (Membership m : getManager().getMemberships(project)) {
                if (m.getUser() != null && !currentUser.getUser().getId().equals(m.getUser().getId())) {
                    users.add(new RedmineUser(m.getUser()));
                }
            }
        } catch (RedmineException ex) {
            // TODO Notify user that Redmine internal error has happened
            Redmine.LOG.log(Level.SEVERE, "Can't get Redmine Users", ex);
        }
        return users;
    }

    public List<Tracker> getTrackers() {
        try {
            return getManager().getTrackers();
        } catch (NotFoundException ex) {
            // TODO Notify user that the issue no longer exists
            Redmine.LOG.log(Level.SEVERE, "Can't get Redmine Issue Trackers", ex);
        } catch (RedmineException ex) {
            // TODO Notify user that Redmine internal error has happened
            Redmine.LOG.log(Level.SEVERE, "Can't get Redmine Issue Trackers", ex);
        }
        return Collections.<Tracker>emptyList();
    }
    
    public List<TimeEntryActivity> getTimeEntryActivities() {
        // @todo: Check if caching is sensible
        try {
            return getManager().getTimeEntryActivities();
        } catch (RedmineException ex) {
            // TODO Notify user that Redmine internal error has happened
            Redmine.LOG.log(Level.INFO, "Failed to Redmine Time Entry Activities (either API is missing or no permission)", ex);
        }
        return fallbackTimeActivityEntries;
    }

    public IssueStatus getStatus(int id) {
        for (IssueStatus issueStatus : getStatuses()) {
            if (id == issueStatus.getId()) {
                return issueStatus;
            }
        }
        return null;
    }

    public Collection<? extends IssueStatus> getStatuses() {
        Collection<? extends IssueStatus> c = getLookup().lookupAll(IssueStatus.class);
        if (!c.isEmpty()) {
            return c;
        }
        try {
            c = getManager().getStatuses();
        } catch (NotFoundException ex) {
            DialogDisplayer.getDefault().notify(new NotifyDescriptor.Message(
                    "Can't get Issue Statuses from Redmine:\n" + ex.getMessage(), NotifyDescriptor.ERROR_MESSAGE));
            Redmine.LOG.log(Level.SEVERE, "Can't get Issue Statuses from Redmine", ex);
        } catch (Exception ex) {
            Redmine.LOG.log(Level.SEVERE, "Can't get Issue Statuses from Redmine", ex);
        }
        if (c.isEmpty()) {
            c = Collections.singleton(new IssueStatus(-1, "[n/a]"));
        }
        for (IssueStatus issueStatus : c) {
            ic.add(issueStatus);
        }
        return c;
    }

    public Collection<? extends IssueCategory> reloadIssueCategories() {
        for (IssueCategory issueCategory : getLookup().lookupAll(IssueCategory.class)) {
            ic.remove(issueCategory);
        }
        return getIssueCategories();
    }

    public Collection<? extends IssueCategory> getIssueCategories() {
        Collection<? extends IssueCategory> c = getLookup().lookupAll(IssueCategory.class);
        if (!c.isEmpty()) {
            return c;
        }
        try {
            c = getManager().getCategories(project.getId());
        } catch (NotFoundException ex) {
            DialogDisplayer.getDefault().notify(new NotifyDescriptor.Message(
                    "Can't get Issue Categories for Redmine Project " + project.getName()
                    + ":\n" + ex.getMessage(), NotifyDescriptor.ERROR_MESSAGE));
            Redmine.LOG.log(Level.SEVERE, "Can't get Issue Categories for Redmine Project " + project.getName(), ex);
        } catch (Exception ex) {
            Redmine.LOG.log(Level.SEVERE, "Can't get Issue Categories for Redmine Project " + project.getName(), ex);
        }
        if (c != null) {
//      if (c.isEmpty()) {
//         IssueCategory category = new IssueCategory();
//         category.setId(-1);
//         category.setName("[n/a]");
//         c = Collections.singleton(category);
//      }
            for (IssueCategory category : c) {
                category.setProject(null);
                category.setAssignee(null);
                ic.add(category);
            }
        }
        return c;
    }

    public Collection<? extends Version> reloadVersions() {
        for (Version v : getLookup().lookupAll(Version.class)) {
            ic.remove(v);
        }
        return getVersions();
    }

    public List<Version> getVersions() {
        try {
            return getManager().getVersions(project.getId());
        } catch (Exception ex) {
            Redmine.LOG.log(Level.SEVERE, "Can't get versions for project " + project.getName(), ex);
        }
        // TODO: return a default set of Categories
        return Collections.<Version>emptyList();
    }

    public List<IssuePriority> getIssuePriorities() {
        if (issuePriorities == null) {
            try {
                // since Redmine V2.2.0
                issuePriorities = getManager().getIssuePriorities();
                Collections.reverse(issuePriorities);
            } catch (Exception ex) {
                // LOG on info level, as SEVERE causes 
                issuePriorities = fallbackIssuePriorities;
                Redmine.LOG.log(Level.INFO, "Can't get issue priorities, using defaults", ex);
            }
        }
        return issuePriorities;
    }

    /*public IssueCache<RedmineIssue> getIssueCache() {
     synchronized (CACHE_LOCK) {
     if (cache == null) {
     cache = new RedmineIssueCache();
     }
     return cache;
     }
     }*/
    public Collection<RedmineIssue> simpleSearch(String string) {
        try {
            List<Issue> resultIssues = new LinkedList<>();

            try {
                resultIssues.add(getManager().getIssueById(Integer.parseInt(string)));
            } catch (NumberFormatException ex) {
            } catch (NotFoundException ex) {
            }

            resultIssues.addAll(getManager().getIssuesBySummary(
                    project.getIdentifier(),
                    "*" + string + "*"));

            List<RedmineIssue> redmineIssues = new LinkedList<>();
            for (Issue issue : resultIssues) {
                RedmineIssue redmineIssue = issueCache.cachedRedmineIssue(issue);
                redmineIssues.add(redmineIssue);
            }
            return redmineIssues;
        } catch (NotFoundException ex) {
            // TODO Notify user that the issue no longer exists
            Redmine.LOG.log(Level.SEVERE, "Can't search for Redmine issues", ex);
        } catch (RedmineException ex) {
            // TODO Notify user that Redmine internal error has happened
            Redmine.LOG.log(Level.SEVERE, "Can't search for Redmine issues", ex);
        }

        return Collections.<RedmineIssue>emptyList();
    }

    public Lookup getLookup() {
        if (lookup == null) {
//         ic.add(getIssueCache());
            lookup = new AbstractLookup(ic);
            //lookup = Lookups.fixed(getIssueCache());
        }
        return lookup;
    }
    
    public final RedmineManager getManager() throws RedmineException {
        AuthMode authMode = getAuthMode();
        if (manager == null) {
            if (authMode == null) {
                throw new IllegalArgumentException("authMode must be set");
            }
            if (authMode == AuthMode.AccessKey) {
                manager = new RedmineManager(getUrl(), getAccessKey());
            } else {
                manager = new RedmineManager(getUrl());
                manager.setLogin(getUsername());
                manager.setPassword(getPassword() == null ? "" : String.valueOf(getPassword()));
            }
            currentUser = new RedmineUser(manager.getCurrentUser(), true);
            manager.setObjectsPerPage(100);
        }
        return manager;
    }

    public RedmineUser getCurrentUser() {
        return currentUser;
    }

    private RequestProcessor getRefreshProcessor() {
        if (refreshProcessor == null) {
            refreshProcessor = new RequestProcessor("Redmine refresh - " + getDisplayName()); // NOI18N
        }
        return refreshProcessor;
    }

    private void setupIssueRefreshTask() {
        if (refreshIssuesTask == null) {
            refreshIssuesTask = getRefreshProcessor().create(new Runnable() {
                @Override
                public void run() {
                    Set<String> ids;
                    synchronized (issuesToRefresh) {
                        ids = new HashSet<String>(issuesToRefresh);
                    }
                    if (ids.isEmpty()) {
                        Redmine.LOG.log(Level.FINE, "no issues to refresh {0}",
                                getDisplayName()); // NOI18N
                        return;
                    }
                    Redmine.LOG.log(Level.FINER, "preparing to refresh issue {0} - {1}",
                            new Object[]{getDisplayName(), ids}); // NOI18N
//               GetMultiTaskDataCommand cmd = new GetMultiTaskDataCommand(RedmineRepository.this, ids, new IssuesCollector());
//               getExecutor().execute(cmd, false);
                    scheduleIssueRefresh();
                }
            });
            scheduleIssueRefresh();
        }
    }

    private void setupQueryRefreshTask() {
        if (refreshQueryTask == null) {
            refreshQueryTask = getRefreshProcessor().create(new Runnable() {
                @Override
                public void run() {
                    try {
                        Set<RedmineQuery> queries;
                        synchronized (refreshQueryTask) {
                            queries = new HashSet<RedmineQuery>(queriesToRefresh);
                        }
                        if (queries.isEmpty()) {
                            Redmine.LOG.log(Level.FINE, "no queries to refresh {0}",
                                    new Object[]{getDisplayName()}); // NOI18N
                            return;
                        }
                        for (RedmineQuery q : queries) {
                            Redmine.LOG.log(Level.FINER, "preparing to refresh query {0} - {1}",
                                    new Object[]{q.getDisplayName(), getDisplayName()}); // NOI18N
                            RedmineQueryController qc = q.getController();
                            qc.autoRefresh();
                        }
                    } finally {
                        scheduleQueryRefresh();
                    }
                }
            });
            scheduleQueryRefresh();
        }
    }

    private void scheduleIssueRefresh() {
        int delay = RedmineConfig.getInstance().getIssueRefreshInterval();
        Redmine.LOG.log(Level.FINE, "scheduling issue refresh for repository {0} in {1} minute(s)",
                new Object[]{getDisplayName(), delay}); // NOI18N
        if (delay < 5 && System.getProperty("netbeans.t9y.redmine.force.refresh.delay") == null) { // t9y: Testability
            Redmine.LOG.log(Level.WARNING, " wrong issue refresh delay {0}. Falling back to default {0}",
                    new Object[]{delay, RedmineConfig.DEFAULT_ISSUE_REFRESH}); // NOI18N
            delay = RedmineConfig.DEFAULT_ISSUE_REFRESH;
        }
        refreshIssuesTask.schedule(delay * 60 * 1000); // given in minutes
    }

    private void scheduleQueryRefresh() {
        String schedule = System.getProperty("netbeans.t9y.redmine.force.refresh.schedule", "");
        if (!schedule.isEmpty()) {
            int delay = Integer.parseInt(schedule);
            refreshQueryTask.schedule(delay);
            return;
        }

        int delay = RedmineConfig.getInstance().getQueryRefreshInterval();
        Redmine.LOG.log(Level.FINE, "scheduling query refresh for repository {0} in {1} minute(s)",
                new Object[]{getDisplayName(), delay}); // NOI18N
        if (delay < 5) {
            Redmine.LOG.log(Level.WARNING, " wrong query refresh delay {0}. Falling back to default {0}",
                    new Object[]{delay, RedmineConfig.DEFAULT_QUERY_REFRESH}); // NOI18N
            delay = RedmineConfig.DEFAULT_QUERY_REFRESH;
        }
        refreshQueryTask.schedule(delay * 60 * 1000); // given in minutes
    }

    public void stopRefreshing(String id) {
        Redmine.LOG.log(Level.FINE, "removing issue {0} from refresh on repository {1}",
                new Object[]{id, getDisplayName()}); // NOI18N
        synchronized (issuesToRefresh) {
            issuesToRefresh.remove(id);
        }
    }

    public void scheduleForRefresh(String id) {
        Redmine.LOG.log(Level.FINE, "scheduling issue {0} for refresh on repository {0}",
                new Object[]{id, getDisplayName()}); // NOI18N
        synchronized (issuesToRefresh) {
            issuesToRefresh.add(id);
        }
        setupIssueRefreshTask();
    }

    public void scheduleForRefresh(RedmineQuery query) {
        Redmine.LOG.log(Level.FINE, "scheduling query {0} for refresh on repository {1}",
                new Object[]{query.getDisplayName(), getDisplayName()}); // NOI18N
        synchronized (queriesToRefresh) {
            queriesToRefresh.add(query);
        }
        setupQueryRefreshTask();
    }

    public void stopRefreshing(RedmineQuery query) {
        Redmine.LOG.log(Level.FINE, "removing query {0} from refresh on repository {1}",
                new Object[]{query.getDisplayName(), getDisplayName()}); // NOI18N
        synchronized (queriesToRefresh) {
            queriesToRefresh.remove(query);
        }
    }

    @Override
    public String toString() {
        return getClass().getSimpleName()
                + "["
                + getDisplayName()
                + "]";
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof RedmineRepository)) {
            return false;
        }
        RedmineRepository other = (RedmineRepository) obj;
        return Is.equals(this.getDisplayName(), other.getDisplayName())
                && Is.equals(this.getUrl(), other.getUrl())
                && Is.equals(this.project, other.project);
    }

    @Override
    public int hashCode() {
        int hash = 3;
        hash = 97 * hash + (this.getDisplayName() != null ? this.getDisplayName().hashCode() : 0);
        hash = 97 * hash + (this.getUrl() != null ? this.getUrl().hashCode() : 0);
        hash = 97 * hash + (this.project != null ? this.project.hashCode() : 0);
        return hash;
    }
    // 
    // Change Support
    //
    private transient final PropertyChangeSupport propertyChangeSupport = new PropertyChangeSupport(this);

    public void addPropertyChangeListener(PropertyChangeListener listener) {
        propertyChangeSupport.addPropertyChangeListener(listener);
    }

    public void removePropertyChangeListener(PropertyChangeListener listener) {
        propertyChangeSupport.removePropertyChangeListener(listener);
    }
    
    public boolean isFeatureWatchers() {
        if (info == null) {
            return false;
        }
        String supportsWatchers = info.getValue(PROPERTY_FEATURE_WATCHERS);
        if(supportsWatchers == null) {
            return false;
        } else {
            return Boolean.parseBoolean(supportsWatchers);
        }
    }

    public IssuePriority getDefaultIssuePriority() {
        for (IssuePriority issuePriority : getIssuePriorities()) {
            if (issuePriority.isDefault()) {
                return issuePriority;
            }
        }
        return null;
    }
    
    public static IssuePriority createIssuePriority(Integer id, String name, boolean isDefault) {
        IssuePriority ip = new IssuePriority();
        ip.setId(id);
        ip.setName(name);
        ip.setDefault(isDefault);
        return ip;
    }

    private List<IssuePriority> issuePriorities;

    public IssuePriority getIssuePriority(Integer id) {
        for(IssuePriority ip: getIssuePriorities()) {
            if(ip.getId().equals(id)) {
                return ip;
            }
        }
        return null;
    }
}
