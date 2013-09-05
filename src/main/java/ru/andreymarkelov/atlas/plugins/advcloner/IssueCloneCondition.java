package ru.andreymarkelov.atlas.plugins.advcloner;

import com.atlassian.crowd.embedded.api.User;
import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.plugin.webfragment.conditions.AbstractIssueCondition;
import com.atlassian.jira.plugin.webfragment.model.JiraHelper;
import com.atlassian.jira.security.Permissions;

public class IssueCloneCondition extends AbstractIssueCondition {
    @Override
    public boolean shouldDisplay(User user, Issue issue, JiraHelper jh) {
        if (user == null || issue == null) {
            return false;
        }

        return ComponentAccessor.getPermissionManager().hasPermission(Permissions.CREATE_ISSUE, issue, user);
    }
}
