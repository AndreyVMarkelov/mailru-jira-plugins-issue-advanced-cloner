package ru.andreymarkelov.atlas.plugins.advcloner;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.atlassian.sal.api.ApplicationProperties;

public class JiraVersion {
    private final static String VERSION_REGEX = "^(\\d+)\\.(\\d+)";

    private int majorVersion;
    private int minorVersion;

    public  JiraVersion(ApplicationProperties appProps) {
        Matcher versionMatcher = Pattern.compile(VERSION_REGEX).matcher(appProps.getVersion());
        versionMatcher.find();
        majorVersion = Integer.decode(versionMatcher.group(1));
        minorVersion = Integer.decode(versionMatcher.group(2));
    }

    public boolean isJira51() {
        return (majorVersion == 5 && minorVersion < 2);
    }

    public boolean isJira52() {
        return (majorVersion == 5 && minorVersion >= 2);
    }

    public boolean isMoreJira51() {
        return (majorVersion == 5 && minorVersion >= 1);
    }

    public boolean isMoreJira52() {
        return (majorVersion == 5 && minorVersion >= 2);
    }
}
