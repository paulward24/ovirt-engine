package org.ovirt.engine.core.dal.dbbroker.auditloghandling;

import java.text.MessageFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang.StringUtils;
import org.ovirt.engine.core.common.AuditLogSeverity;
import org.ovirt.engine.core.common.AuditLogType;
import org.ovirt.engine.core.common.businessentities.AuditLog;
import org.ovirt.engine.core.compat.Guid;
import org.ovirt.engine.core.compat.backendcompat.TypeCompat;
import org.ovirt.engine.core.dal.dbbroker.DbFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public final class AuditLogDirector {
    private static final Logger log = LoggerFactory.getLogger(AuditLogDirector.class);
    private static final Pattern pattern = Pattern.compile("\\$\\{\\w*\\}"); // match ${<alphanumeric>...}
    static final String UNKNOWN_VARIABLE_VALUE = "<UNKNOWN>";
    static final String UNKNOWN_REASON_VALUE = "Not Specified";
    static final String REASON_TOKEN = "reason";
    private static final ResourceBundle resourceBundle = getResourceBundle();

    static ResourceBundle getResourceBundle() {
        try {
            return ResourceBundle.getBundle(getResourceBundleName());
        } catch (MissingResourceException e) {
            throw new RuntimeException("Could not find ResourceBundle file '" + getResourceBundleName() +"'.");
        }
    }

    private static String getResourceBundleName() {
        return "bundles/AuditLogMessages";
    }

    public static String getMessage(AuditLogType logType) {
        return StringUtils.defaultString(getMessageOrNull(logType));
    }

    protected static String getMessageOrNull(AuditLogType logType) {
        final String key = logType.name();
        try {
            return resourceBundle.getString(key);
        } catch (Exception e) {
            log.error("Key '{}' is not translated in '{}'", key, getResourceBundleName());
            return null;
        }
    }

    public static void log(AuditLogableBase auditLogable) {
        AuditLogType logType = auditLogable.getAuditLogTypeValue();
        log(auditLogable, logType);
    }

    public static void log(AuditLogableBase auditLogable, AuditLogType logType) {
        log(auditLogable, logType, "");
    }

    public static void log(AuditLogableBase auditLogable, AuditLogType logType, String loggerString) {
        if (!logType.shouldBeLogged()) {
            return;
        }

        updateTimeoutLogableObject(auditLogable, logType);

        if (auditLogable.getLegal()) {
            saveToDb(auditLogable, logType, loggerString);
        }
    }

    private static void saveToDb(AuditLogableBase auditLogable, AuditLogType logType, String loggerString) {
        AuditLogSeverity severity = logType.getSeverity();
        AuditLog auditLog = createAuditLog(auditLogable, logType, loggerString, severity);

        if (auditLog == null) {
            log.warn("Unable to create AuditLog");
        } else {
            setPropertiesFromAuditLogableBase(auditLogable, auditLog);

            getDbFacadeInstance().getAuditLogDao().save(auditLog);
            logMessage(severity, getMessageToLog(loggerString, auditLog));
        }
    }

    private static void setPropertiesFromAuditLogableBase(AuditLogableBase auditLogable, AuditLog auditLog) {
        auditLog.setStorageDomainId(auditLogable.getStorageDomainId());
        auditLog.setStorageDomainName(auditLogable.getStorageDomainName());
        auditLog.setStoragePoolId(auditLogable.getStoragePoolId());
        auditLog.setStoragePoolName(auditLogable.getStoragePoolName());
        auditLog.setVdsGroupId(auditLogable.getVdsGroupId());
        auditLog.setVdsGroupName(auditLogable.getVdsGroupName());
        auditLog.setCorrelationId(auditLogable.getCorrelationId());
        auditLog.setJobId(auditLogable.getJobId());
        auditLog.setGlusterVolumeId(auditLogable.getGlusterVolumeId());
        auditLog.setGlusterVolumeName(auditLogable.getGlusterVolumeName());
        auditLog.setExternal(auditLogable.isExternal());
        auditLog.setQuotaId(auditLogable.getQuotaIdForLog());
        auditLog.setQuotaName(auditLogable.getQuotaNameForLog());
        auditLog.setCallStack(auditLogable.getCallStack());
    }

    private static void logMessage(AuditLogSeverity severity, String logMessage) {
        switch (severity) {
        case NORMAL:
            log.info(logMessage);
            break;
        case ERROR:
            log.error(logMessage);
            break;
        case ALERT:
        case WARNING:
        default:
            log.warn(logMessage);
            break;
        }
    }

    private static String getMessageToLog(String loggerString, AuditLog auditLog) {
        if (loggerString.isEmpty()) {
            return auditLog.toStringForLogging();
        } else {
            return MessageFormat.format(loggerString, auditLog.getMessage());
        }
    }

    private static AuditLog createAuditLog(AuditLogableBase auditLogable, AuditLogType logType, String loggerString, AuditLogSeverity severity) {
        // handle external log messages invoked by plugins via the API
        if (auditLogable.isExternal()) {
            String resolvedMessage = loggerString; // message is sent as an argument, no need to resolve.
            return new AuditLog(logType,
                    severity,
                    resolvedMessage,
                    auditLogable.getUserId(),
                    auditLogable.getUserName(),
                    auditLogable.getVmIdRef(),
                    auditLogable.getVmIdRef() != null ? getDbFacadeInstance().getVmDao().get(auditLogable.getVmIdRef()).getName() : null,
                    auditLogable.getVdsIdRef(),
                    auditLogable.getVdsIdRef() != null ? getDbFacadeInstance().getVdsDao().get(auditLogable.getVdsIdRef()).getName() : null,
                    auditLogable.getVmTemplateIdRef(),
                    auditLogable.getVmTemplateIdRef() != null ? getDbFacadeInstance().getVmTemplateDao().get(auditLogable.getVmTemplateIdRef()).getName() : null,
                    auditLogable.getOrigin(),
                    auditLogable.getCustomEventId(),
                    auditLogable.getEventFloodInSec(),
                    auditLogable.getCustomData());
        }

        final String messageByType = getMessageOrNull(logType);
        if (messageByType == null) {
            return null;
        } else {
            // Application log message from AuditLogMessages
            String resolvedMessage = resolveMessage(messageByType, auditLogable);
            return new AuditLog(logType, severity, resolvedMessage, auditLogable.getUserId(),
                    auditLogable.getUserName(), auditLogable.getVmIdRef(), auditLogable.getVmName(),
                    auditLogable.getVdsIdRef(), auditLogable.getVdsName(), auditLogable.getVmTemplateIdRef(),
                    auditLogable.getVmTemplateName());
        }
    }

    /**
     * Update the logged object timeout attribute by log type definition
     * @param auditLogable
     *            the logable object to be updated
     * @param logType
     *            the log type which determine if timeout is used for it
     */
    private static void updateTimeoutLogableObject(AuditLogableBase auditLogable, AuditLogType logType) {
        int eventFloodRate = (auditLogable.isExternal() && auditLogable.getEventFloodInSec() == 0)
                ?
                30 // Minimal default duration for External Events is 30 seconds.
                :
                logType.getEventFloodRate();
        if (eventFloodRate > 0) {
            auditLogable.setEndTime(TimeUnit.SECONDS.toMillis(eventFloodRate));
            auditLogable.setTimeoutObjectId(composeObjectId(auditLogable, logType));
        }
    }

    public static DbFacade getDbFacadeInstance() {
        return DbFacade.getInstance();
    }

    /**
     * Composes an object id from all log id's to identify uniquely each instance.
     * @param logable
     *            the object to log
     * @param logType
     *            the log type associated with the object
     * @return a unique object id
     */
    private static String composeObjectId(AuditLogableBase logable, AuditLogType logType) {
        final char DELIMITER = ',';
        StringBuilder sb = new StringBuilder();
        sb.append("type=");
        sb.append(logType);
        sb.append(DELIMITER);
        sb.append("sd=");
        sb.append(logable.getStorageDomainId() == null ? "" : logable.getStorageDomainId().toString());
        sb.append(DELIMITER);
        sb.append("dc=");
        sb.append(logable.getStoragePoolId() == null ? "" : logable.getStoragePoolId().toString());
        sb.append(DELIMITER);
        sb.append("user=");
        sb.append(logable.getUserId() == null ? "" : logable.getUserId().toString());
        sb.append(DELIMITER);
        sb.append("cluster=");
        sb.append(logable.getVdsGroupId().toString());
        sb.append(DELIMITER);
        sb.append("vds=");
        sb.append(logable.getVdsId().toString());
        sb.append(DELIMITER);
        sb.append("vm=");
        sb.append(logable.getVmId().equals(Guid.Empty) ? "" : logable.getVmId().toString());
        sb.append(DELIMITER);
        sb.append("template=");
        sb.append(logable.getVmTemplateId().equals(Guid.Empty) ? "" : logable.getVmTemplateId().toString());
        sb.append(DELIMITER);
        sb.append("customId=");
        sb.append(StringUtils.defaultString(logable.getCustomId()));
        sb.append(DELIMITER);

        return sb.toString();
    }

    static String resolveMessage(String message, AuditLogableBase logable) {
        String returnValue = message;
        if (logable != null) {
            Map<String, String> map = getAvailableValues(message, logable);
            returnValue = resolveMessage(message, map);
        }
        return returnValue;
    }

    /**
     * Resolves a message which contains place holders by replacing them with the value from the map.
     *
     * @param message
     *            A text representing a message with place holders
     * @param values
     *            a map of the place holder to its values
     * @return a resolved message
     */
    public static String resolveMessage(String message, Map<String, String> values) {
        Matcher matcher = pattern.matcher(message);

        StringBuffer buffer = new StringBuffer();
        String value;
        String token;
        while (matcher.find()) {
            token = matcher.group();

            // remove leading ${ and trailing }
            token = token.substring(2, token.length() - 1);

            // get value from value map
            value = values.get(token.toLowerCase());
            if (value == null || value.isEmpty()) {
                // replace value with UNKNOWN_VARIABLE_VALUE if value not defined
                switch(token.toLowerCase()) {
                    case REASON_TOKEN:
                        value = UNKNOWN_REASON_VALUE;
                        break;
                    default:
                        value = UNKNOWN_VARIABLE_VALUE;
                }
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(value)); // put the value into message
        }

        // append the rest of the message
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static Set<String> resolvePlaceHolders(String message) {
        Set<String> result = new HashSet<String>();
        Matcher matcher = pattern.matcher(message);

        String token;
        while (matcher.find()) {
            token = matcher.group();

            // remove leading ${ and trailing }
            token = token.substring(2, token.length() - 1);
            result.add(token.toLowerCase());
        }
        return result;
    }

    private static Map<String, String> getAvailableValues(String message, AuditLogableBase logable) {
        Map<String, String> returnValue = new HashMap<String, String>(logable.getCustomValues());
        Set<String> attributes = resolvePlaceHolders(message);
        if (attributes != null && attributes.size() > 0) {
            TypeCompat.getPropertyValues(logable, attributes, returnValue);
        }
        return returnValue;
    }
}
