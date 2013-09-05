package ru.andreymarkelov.atlas.plugins.advcloner;

import java.io.File;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.ofbiz.core.entity.GenericValue;

import com.atlassian.crowd.embedded.api.User;
import com.atlassian.jira.bc.issue.IssueService;
import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.config.SubTaskManager;
import com.atlassian.jira.config.properties.APKeys;
import com.atlassian.sal.api.ApplicationProperties;
import com.atlassian.jira.exception.CreateException;
import com.atlassian.jira.issue.AttachmentManager;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.IssueFactory;
import com.atlassian.jira.issue.IssueManager;
import com.atlassian.jira.issue.MutableIssue;
import com.atlassian.jira.issue.attachment.Attachment;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.link.IssueLink;
import com.atlassian.jira.issue.link.IssueLinkManager;
import com.atlassian.jira.issue.link.IssueLinkType;
import com.atlassian.jira.issue.link.IssueLinkTypeManager;
import com.atlassian.jira.issue.link.RemoteIssueLink;
import com.atlassian.jira.issue.link.RemoteIssueLinkBuilder;
import com.atlassian.jira.issue.link.RemoteIssueLinkManager;
import com.atlassian.jira.issue.watchers.WatcherManager;
import com.atlassian.jira.project.version.Version;
import com.atlassian.jira.security.PermissionManager;
import com.atlassian.jira.security.Permissions;
import com.atlassian.jira.util.AttachmentUtils;
import com.atlassian.jira.web.action.issue.AbstractViewIssue;
import com.atlassian.jira.web.util.AttachmentException;
import com.google.common.base.Strings;
import com.opensymphony.util.TextUtils;

/**
 * Multiple clone issues action.
 * 
 * @author Andrey Markelov
 */
public class IssueClonerWithCount extends AbstractViewIssue {
    /**
     * Unique ID.
     */
    private static final long serialVersionUID = -7013122116781342738L;

    private final JiraVersion jiraVersion;
    private final PermissionManager permissionManager;
    private final IssueLinkManager issueLinkManager;
    private final RemoteIssueLinkManager remoteIssueLinkManager;
    private final IssueLinkTypeManager issueLinkTypeManager;
    private final AttachmentManager attachmentManager;
    private final IssueFactory issueFactory;
    private final IssueManager issueManager;
    private final WatcherManager watcherManager;
    private IssueLinkType cloneIssueLinkType;

    private String cloneIssueLinkTypeName;
    private boolean cloneLinks;
    private boolean cloneAttachments;
    private boolean cloneWatchers;
    private boolean cloneComments;
    private String issueCount;
    private String newName;
    private String[] fields;
    private final Map<Long, Long> newIssueIdMap = new HashMap<Long, Long>();

    public IssueClonerWithCount(
            ApplicationProperties applicationProperties,
            PermissionManager permissionManager,
            IssueLinkManager issueLinkManager,
            RemoteIssueLinkManager remoteIssueLinkManager,
            IssueLinkTypeManager issueLinkTypeManager,
            SubTaskManager subTaskManager,
            AttachmentManager attachmentManager,
            IssueFactory issueFactory,
            IssueService issueService,
            IssueManager issueManager,
            WatcherManager watcherManager) {
        super(subTaskManager);
        this.permissionManager = permissionManager;
        this.jiraVersion = new JiraVersion(applicationProperties);
        this.issueLinkManager = issueLinkManager;
        this.remoteIssueLinkManager = remoteIssueLinkManager;
        this.issueLinkTypeManager = issueLinkTypeManager;
        this.attachmentManager = attachmentManager;
        this.issueFactory = issueFactory;
        this.issueManager = issueManager;
        this.watcherManager = watcherManager;
    }

    /**
     * Clone attachments.
     */
    private void cloneIssueAttachments(Issue originalIssue, Issue clone) throws CreateException {
        if (isCloneAttachments() && attachmentManager.attachmentsEnabled()) {
            final List<Attachment> attachments = attachmentManager.getAttachments(originalIssue);
            final String remoteUserName = getLoggedInUser() == null ? null : getLoggedInUser().getName();
            for (Attachment attachment : attachments) {
                File attachmentFile = AttachmentUtils.getAttachmentFile(attachment);
                if (attachmentFile.exists() && attachmentFile.canRead()) {
                    try {
                        attachmentManager.createAttachmentCopySourceFile(attachmentFile, attachment.getFilename(), attachment.getMimetype(), remoteUserName, clone, Collections.EMPTY_MAP, new Timestamp(System.currentTimeMillis()));
                    } catch (AttachmentException e) {
                        log.warn("Could not clone attachment with id '" + attachment.getId() + "' and file path '" + attachmentFile.getAbsolutePath() + "' for issue with id '" + clone.getId() + "' and key '" + clone.getKey() + "'.", e);
                    }
                } else {
                    log.warn("Could not clone attachment with id '" + attachment.getId() + "' and file path '" + attachmentFile.getAbsolutePath() + "' for issue with id '" + clone.getId() + "' and key '" + clone.getKey() + "', " +
                             "because the file path " + (attachmentFile.exists() ? "is not readable." : "does not exist."));
                }
            }
        }
    }

    /**
     * Clone issue links.
     */
    private void cloneIssueLinks(Issue originalIssue, Issue clone, Set<Long> originalIssueIdSet) throws CreateException {
        if (isCloneLinks() && issueLinkManager.isLinkingEnabled()) {
            Collection<IssueLink> inwardLinks = issueLinkManager.getInwardLinks(originalIssue.getId());
            for (final IssueLink issueLink : inwardLinks) {
                if (copyLink(issueLink)) {
                    Long sourceIssueId = issueLink.getSourceId();
                    if (originalIssueIdSet.contains(sourceIssueId)) {
                        sourceIssueId = newIssueIdMap.get(sourceIssueId);
                    }
                    if (sourceIssueId != null) {
                        issueLinkManager.createIssueLink(sourceIssueId, clone.getId(), issueLink.getIssueLinkType().getId(), null, getLoggedInUser());
                    }
                }
            }

            Collection<IssueLink> outwardLinks = issueLinkManager.getOutwardLinks(originalIssue.getId());
            for (final IssueLink issueLink : outwardLinks) {
                if (copyLink(issueLink)) {
                    Long destinationId = issueLink.getDestinationId();
                    if (originalIssueIdSet.contains(destinationId)) {
                        destinationId = newIssueIdMap.get(destinationId);
                    }
                    if (destinationId != null) {
                        issueLinkManager.createIssueLink(clone.getId(), destinationId, issueLink.getIssueLinkType().getId(), null, getLoggedInUser());
                    }
                }
            }

            final List<RemoteIssueLink> originalLinks = remoteIssueLinkManager.getRemoteIssueLinksForIssue(originalIssue);
            for (final RemoteIssueLink originalLink : originalLinks) {
                final RemoteIssueLink link = new RemoteIssueLinkBuilder(originalLink).id(null).issueId(clone.getId()).build();
                remoteIssueLinkManager.createRemoteIssueLink(link, getLoggedInUser());
            }
        }
    }

    private void cloneWatchers(Issue originalIssue, Issue clone) {
        if (isCloneWatchers() && watcherManager.isWatchingEnabled()) {
            List<User> watches = ComponentAccessor.getIssueManager().getWatchers(originalIssue);
            if (watches != null) {
                for (User w : watches) {
                    watcherManager.startWatching(w, clone);
                }
            }
        }
    }

    /**
     * Check copy link.
     */
    private boolean copyLink(IssueLink issueLink) {
        return !issueLink.isSystemLink() &&
               (getCloneIssueLinkType() == null || !getCloneIssueLinkType().getId().equals(issueLink.getIssueLinkType().getId()));
    }

    @Override
    public String doDefault() throws Exception {
        ComponentAccessor.getCustomFieldManager().getCustomFieldObjects(getIssueObject());

        this.cloneLinks = false;
        this.cloneAttachments = false;
        this.issueCount = "1";
        this.newName = getClonePrefix() + getIssueObject().getSummary();

        return INPUT;
    }

    @Override
    @com.atlassian.jira.security.xsrf.RequiresXsrfCheck
    protected String doExecute() throws Exception {
        List<Issue> newIssues = new ArrayList<Issue>();
        try {
            int iIssueCount = Integer.parseInt(issueCount);
            for (int i = 0; i < iIssueCount; i++) {
                MutableIssue mu = issueFactory.cloneIssue(getIssueObject());
                setFields(mu);
                final Issue newIssue = issueManager.createIssueObject(getLoggedInUser(), mu);
                newIssues.add(newIssue);
                newIssueIdMap.put(getIssueObject().getId(), newIssue.getId());

                final IssueLinkType cloneIssueLinkType = getCloneIssueLinkType();
                if (cloneIssueLinkType != null) {
                    issueLinkManager.createIssueLink(getIssueObject().getId(), newIssue.getId(), cloneIssueLinkType.getId(), null, getLoggedInUser());
                }

                cloneIssueAttachments(getIssueObject(), newIssue);

                cloneWatchers(getIssueObject(), newIssue);

                Set<Long> originalIssueIdSet = getOriginalIssueIdSet(getIssueObject());
                cloneIssueLinks(getIssueObject(), newIssue, originalIssueIdSet);
            }

            return doPostCreationTasks(newIssues);
        } catch (Exception e) {
            log.error(e, e);
            addErrorMessage(getText("admin.errors.exception")+" " + e);
            return ERROR;
        }
    }

    /**
     * Get original issue HREF.
     */
    protected String doPostCreationTasks(List<Issue> newIssues) throws Exception {
        if (newIssues.size() > 0) {
            return redirect("/browse/" + newIssues.get(newIssues.size() - 1).getKey());
        } else {
            return redirect("/browse/" + getIssueObject().getKey());
        }
    }

    @Override
    protected void doValidation() {
        if (newName == null || newName.length() == 0) {
            addErrorMessage("advanced-cloner.errorname");
        }

        try {
            int i = Integer.parseInt(issueCount);
            if (i <= 0) {
                addErrorMessage("advanced-cloner.errorcount");
            }
        } catch (NumberFormatException nex) {
            addErrorMessage("advanced-cloner.errorcount");
        }
        super.doValidation();
    }

    /**
     * Filter archived versions.
     */
    private Collection<Version> filterArchivedVersions(Collection<Version> versions) {
        List<Version> tempVers = new ArrayList<Version>();
        for (Iterator<Version> versionsIt = versions.iterator(); versionsIt.hasNext();) {
            Version version = versionsIt.next();
            if(!version.isArchived()) {
                tempVers.add(version);
            }
        }
        return tempVers;
    }

    /**
     * Get context path.
     */
    public String getBaseUrl() {
        return ComponentAccessor.getApplicationProperties().getDefaultBackedString(APKeys.JIRA_BASEURL);
    }

    /**
     * Get clone link type.
     */
    public IssueLinkType getCloneIssueLinkType() {
        if (cloneIssueLinkType == null) {
            final Collection<IssueLinkType> cloneIssueLinkTypes = issueLinkTypeManager.getIssueLinkTypesByName(getCloneLinkTypeName());
            if (!TextUtils.stringSet(getCloneLinkTypeName())) {
                cloneIssueLinkType = null;
            } else if (cloneIssueLinkTypes == null || cloneIssueLinkTypes.isEmpty()) {
                log.warn("The clone link type '" + getCloneLinkTypeName() + "' does not exist. A link to the original issue will not be created.");
                cloneIssueLinkType = null;
            } else {
                for (Iterator<IssueLinkType> iterator = cloneIssueLinkTypes.iterator(); iterator.hasNext();) {
                    IssueLinkType issueLinkType = iterator.next();
                    if (issueLinkType.getName().equals(getCloneLinkTypeName())) {
                        cloneIssueLinkType = issueLinkType;
                    }
                }
            }
        }

        return cloneIssueLinkType;
    }

    /**
     * Get clone link name.
     */
    public String getCloneLinkTypeName() {
        if (cloneIssueLinkTypeName == null) {
            cloneIssueLinkTypeName = ComponentAccessor.getApplicationProperties().getDefaultBackedString(APKeys.JIRA_CLONE_LINKTYPE_NAME);
        }
        return cloneIssueLinkTypeName;
    }

    /**
     * Get clone prefix.
     */
    public String getClonePrefix() {
        String clonePrefixProperties = ComponentAccessor.getApplicationProperties().getDefaultBackedString(APKeys.JIRA_CLONE_PREFIX);
        return clonePrefixProperties + (Strings.isNullOrEmpty(clonePrefixProperties) ? "" : " ");
    }

    /**
     * Get custom fields of issue.
     */
    public List<CustomField> getCustomFields() {
        return getCustomFieldManager().getCustomFieldObjects(getIssueObject().getProjectObject().getId(), getIssueObject().getIssueTypeObject().getId());
    }

    public String[] getFields() {
        return fields;
    }

    public String getIssueCount() {
        return issueCount;
    }

    public Issue getIssueObject(GenericValue genericValue) {
        return issueFactory.getIssue(genericValue);
    }

    public JiraVersion getJiraVersion() {
        return jiraVersion;
    }

    public String getNewName() {
        return newName;
    }

    /**
     * Fill subtasks.
     */
    private Set<Long> getOriginalIssueIdSet(final Issue originalIssue) {
        Set<Long> originalIssues = new HashSet<Long>();
        originalIssues.add(originalIssue.getId());
        if (getSubTaskManager().isSubTasksEnabled()) {
            for (final Issue issue : originalIssue.getSubTaskObjects()) {
                originalIssues.add(issue.getId());
            }
        }
        return originalIssues;
    }

    public boolean isCanModifyReporter() {
        return permissionManager.hasPermission(Permissions.MODIFY_REPORTER, getIssueObject(), getLoggedInUser());
    }

    public boolean isCloneAttachments() {
        return cloneAttachments;
    }

    public boolean isCloneComments() {
        return cloneComments;
    }

    public boolean isCloneLinks() {
        return cloneLinks;
    }

    public boolean isCloneWatchers() {
        return cloneWatchers;
    }

    private String redirect(String path) {
        return returnCompleteWithInlineRedirect(path);
    }

    public void setCloneAttachments(boolean cloneAttachments) {
        this.cloneAttachments = cloneAttachments;
    }

    public void setCloneComments(boolean cloneComments) {
        this.cloneComments = cloneComments;
    }

    public void setCloneLinks(boolean cloneLinks) {
        this.cloneLinks = cloneLinks;
    }

    public void setCloneWatchers(boolean cloneWatchers) {
        this.cloneWatchers = cloneWatchers;
    }

    /**
     * Set new issue fields.
     */
    protected void setFields(MutableIssue newIssue) {
        Issue originalIssue = getIssueObject();

        newIssue.setSummary(newName);
        newIssue.setCreated(null);
        newIssue.setUpdated(null);
        newIssue.setKey(null);
        newIssue.setVotes(null);
        newIssue.setStatusObject(null);
        newIssue.setWorkflowId(null);
        newIssue.setEstimate(originalIssue.getOriginalEstimate());
        newIssue.setTimeSpent(null);
        newIssue.setResolutionDate(null);
        newIssue.setResolutionObject(null);
        newIssue.setFixVersions(filterArchivedVersions(originalIssue.getFixVersions()));
        newIssue.setAffectedVersions(filterArchivedVersions(originalIssue.getAffectedVersions()));
        newIssue.setWatches(null);

        if (!isCanModifyReporter()) {
            getIssueObject().setReporter(getLoggedInUser());
        }

        List<CustomField> customFields = getCustomFields();
        for (Iterator<CustomField> iterator = customFields.iterator(); iterator.hasNext();) {
            CustomField customField = (CustomField) iterator.next();

            if (Arrays.binarySearch(fields, customField.getId()) < 0) {
                continue;
            }

            Object value = customField.getValue(originalIssue);
            if (value != null) {
                newIssue.setCustomFieldValue(customField, value);
            }
        }
    }

    public void setFields(String[] fields) {
        this.fields = fields;
    }

    public void setIssueCount(String issueCount) {
        this.issueCount = issueCount;
    }

    public void setNewName(String newName) {
        this.newName = newName;
    }
}
