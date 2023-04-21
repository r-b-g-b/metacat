package edu.ucsb.nceas.metacat.properties;

import edu.ucsb.nceas.metacat.shared.MetacatUtilException;
import edu.ucsb.nceas.metacat.util.SystemUtil;
import edu.ucsb.nceas.utilities.GeneralPropertyException;
import edu.ucsb.nceas.utilities.PropertiesMetaData;
import edu.ucsb.nceas.utilities.PropertyNotFoundException;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.dataone.configuration.Settings;

import javax.xml.transform.TransformerException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Vector;

/**
 * A Class that provides a wrapper around standard java.util.Properties to provide backwards
 * compatibility with metacat's original properties implementation
 */
public class PropertiesWrapper {

    private static PropertiesWrapper propertiesWrapper = null;
    private static final Log logMetacat = LogFactory.getLog(PropertiesWrapper.class);

    private Path mainMetadataFilePath = null;
    private PropertiesMetaData mainMetaData = null;

    // Composite of site-specific properties overlaid on top of default properties
    private Properties mainProperties = null;

    // default properties only; not including any site-specific properties
    private Properties defaultProperties = null;

    // Full Path to the default properties file
    // Example: /var/lib/tomcat/webapps/WEB-INF/metacat.properties
    private static Path defaultPropertiesFilePath = null;

    // Full Path to the site-specific properties file to be overlaid on top of the defaults
    // Example: /var/metacat/.metacat/metacat-site.properties
    private static Path sitePropertiesFilePath = null;


    /**
     * private constructor since this is a singleton
     */
    private PropertiesWrapper() throws GeneralPropertyException {
        initialize();
    }

    /**
     * get the current instance of the PropertiesWrapper. To set specific property file locations
     * . use the <code>getNewInstance(Path, Path)</code> method.
     *
     * @return singleton instance of PropertiesWrapper
     * @throws GeneralPropertyException when things go wrong
     */
    protected static PropertiesWrapper getInstance() throws GeneralPropertyException {

        if (propertiesWrapper == null) {
            propertiesWrapper = new PropertiesWrapper();
        }
        return propertiesWrapper;
    }

    /**
     * get an instance of the PropertiesWrapper. <em>WARNING: all calls to this method will
     * re-initialize PropertiesWrapper with the newly-passed values of defaultPropertiesFilePath
     * and sitePropertiesFilePath.</em> If you just want to get an instance without affecting its
     * internals, call <code>getinstance()</code> instead.
     *
     * @param defaultPropertiesFilePath (Can be null) Full path to the default properties file
     *                                  (e.g. /var/lib/tomcat/webapps/WEB-INF/metacat.properties)
     * @param sitePropertiesFilePath (Can be null) Full path to the site properties file
     *                               (e.g. /var/metacat/.metacat/metacat-site.properties)
     * @return singleton instance of PropertiesWrapper
     * @throws GeneralPropertyException when things go wrong
     */
    protected static PropertiesWrapper getNewInstance(Path defaultPropertiesFilePath,
        Path sitePropertiesFilePath) throws GeneralPropertyException {

        PropertiesWrapper.defaultPropertiesFilePath = defaultPropertiesFilePath;
        PropertiesWrapper.sitePropertiesFilePath = sitePropertiesFilePath;
        if (sitePropertiesFilePath != null) {
            BackupPropertiesDelegate.backupDirPath = sitePropertiesFilePath.getParent();
        }
        propertiesWrapper = new PropertiesWrapper();
        return propertiesWrapper;
    }

    /**
     * Get a property value from the properties file.
     *
     * @param propertyName the name of the property requested
     * @return the String value for the property, even if blank. Will never return null
     * @throws PropertyNotFoundException if the passed <code>propertyName</code> key is not in the
     *                                   properties at all
     */
    protected String getProperty(String propertyName) throws PropertyNotFoundException {
        String returnVal;
        if (mainProperties.getProperty(propertyName) != null) {
            returnVal = mainProperties.getProperty(propertyName);
        } else {
            logMetacat.info("did not find the property with key " + propertyName);
            throw new PropertyNotFoundException(
                "PropertiesWrapper.getProperty(): Key/name does not exist in Properties: "
                    + propertyName);
        }
        return returnVal;
    }

    /**
     * Get the DEFAULT property value from the default properties file. Ignore any overriding
     * values in the site properties file
     *
     * @param propertyName the name of the DEFAULT property requested
     * @return the String value for the DEFAULT property, even if blank, or null if the property key
     * is not found
     */
    protected String getDefaultProperty(String propertyName) {
        return defaultProperties.getProperty(propertyName);
    }

    /**
     * Get a set of all property names.
     *
     * @return Vector of property names
     */
    protected Vector<String> getPropertyNames() {
        return new Vector<>(mainProperties.stringPropertyNames());
    }

    /**
     * Get a Set of all property names that start with the groupName prefix.
     *
     * @param groupName the prefix of the keys to search for.
     * @return enumeration of property names
     */
    protected Vector<String> getPropertyNamesByGroup(String groupName) {

        groupName = groupName.trim();
        if (!groupName.endsWith(".")) {
            groupName += (".");
        }
        final String finalGroupName = groupName;
        Vector<String> propNames = getPropertyNames();
        propNames.removeIf(prop -> !prop.startsWith(finalGroupName));
        return propNames;
    }

    /**
     * Get a Map of all properties that start with the groupName prefix.
     *
     * @param groupName the prefix of the keys to search for.
     * @return Map of property names
     */
    protected Map<String, String> getPropertiesByGroup(String groupName)
        throws PropertyNotFoundException {

        Map<String, String> groupPropertyMap = new HashMap<>();
        for (String key : getPropertyNamesByGroup(groupName)) {
            groupPropertyMap.put(key, getProperty(key));
        }
        return groupPropertyMap;
    }

    /**
     * Utility method to add a property value both in memory and to the properties file
     *
     * @param propertyName the name of the property to add
     * @param value        the value for the property
     */
    protected void addProperty(String propertyName, String value) throws GeneralPropertyException {
        mainProperties.setProperty(propertyName, value);
        store();
    }

    /**
     * Utility method to set a property value both in memory and to the properties file
     *
     * @param propertyName the name of the property requested
     * @param newValue     the new value for the property
     */
    protected void setProperty(String propertyName, String newValue)
        throws GeneralPropertyException {
        setPropertyNoPersist(propertyName, newValue);
        store();
    }

    /**
     * Utility method to set a property value in memory. This will NOT cause the property to be
     * written to disk. Use this method to set multiple properties in a row without causing
     * excessive I/O. You must call persistProperties() once you're done setting properties to have
     * them written to disk.
     *
     * @param propertyName the name of the property requested
     * @param newValue     the new value for the property
     */
    protected void setPropertyNoPersist(String propertyName, String newValue)
        throws GeneralPropertyException {
        if (null == mainProperties.getProperty(propertyName)) {
            // TODO: MB - can we get rid of this? Default java.util.Properties behavior is
            //  to add a new entry if it doesn't already exist, when setProperty() is called
            throw new PropertyNotFoundException(
                "Property: " + propertyName + " could not be updated to: " + newValue
                    + " because it does not already exist in properties.");
        }
        mainProperties.setProperty(propertyName, newValue);
    }

    /**
     * Save the properties to a properties file.
     */
    protected void persistProperties() throws GeneralPropertyException {
        store();
    }

    /**
     * Get the main properties metadata. This is retrieved from an xml file that describes the
     * attributes of configurable properties.
     *
     * @return a PropertiesMetaData object with the main properties metadata
     */
    protected PropertiesMetaData getMainMetaData() {
        return mainMetaData;
    }

    protected void doRefresh() throws GeneralPropertyException {
        initialize();
    }

    protected Path getDefaultPropertiesFilePath() {
        return defaultPropertiesFilePath;
    }

    protected Path getSitePropertiesFilePath() {
        return sitePropertiesFilePath;
    }

    protected Path getMainMetadataFilePath() {
        return mainMetadataFilePath;
    }

    /**
     * Initialize the singleton.
     */
    private void initialize() throws GeneralPropertyException {

        logMetacat.debug("Initializing PropertiesWrapper");
        try {
            if (isNotBlankPath(defaultPropertiesFilePath)) {
                if (!Files.exists(defaultPropertiesFilePath)) {
                    logAndThrow(
                        "PropertiesWrapper.initialize(): received non-existent Default Properties "
                            + "File Path: " + defaultPropertiesFilePath, null);
                }
            } else {
                defaultPropertiesFilePath =
                    Paths.get(PropertyService.CONFIG_FILE_DIR, "metacat.properties");
            }
            mainMetadataFilePath = Paths.get(PropertyService.CONFIG_FILE_DIR,
                "metacat.properties.metadata.xml");

            String recommendedExternalDir = SystemUtil.discoverExternalDir();
            PropertyService.setRecommendedExternalDir(recommendedExternalDir);

            // defaultProperties will hold the default configuration from metacat.properties
            defaultProperties = new Properties();
            defaultProperties.load(Files.newBufferedReader(defaultPropertiesFilePath));

            logMetacat.debug("PropertiesWrapper.initialize(): finished "
                + "loading metacat.properties into mainDefaultProperties");

            // mainProperties is (1) the aggregation of the default props from metacat.properties...
            mainProperties = new Properties(defaultProperties);

            // (2)...overlaid with site-specific properties from metacat-site.properties:
            initSitePropertiesFilePath();
            mainProperties.load(Files.newBufferedReader(sitePropertiesFilePath));
            logMetacat.info("PropertiesWrapper.initialize(): populated mainProperties. Used "
                + defaultPropertiesFilePath + " as defaults; overlaid with "
                + sitePropertiesFilePath + " -- for a total of "
                + mainProperties.stringPropertyNames().size() + " Property entries");

            store(); //to persist new value of site properties path

            // include main metacat properties in d1 properties as overrides
            try {
                Settings.getConfiguration();
                //augment with BOTH properties files:
                Settings.augmentConfiguration(defaultPropertiesFilePath.toString());
                Settings.augmentConfiguration(sitePropertiesFilePath.toString());
            } catch (ConfigurationException e) {
                logMetacat.error("Could not augment DataONE properties. " + e.getMessage(), e);
            }

            // mainMetaData holds configuration information about main properties. This is primarily
            // used to display input fields on the configuration page. The information is retrieved
            // from an xml metadata file
            mainMetaData = new PropertiesMetaData(mainMetadataFilePath.toString());

//            if (Files.exists(backupPropertiesDelegate.getBackupDirPath())) {
//
//                SystemUtil.writeStoredBackupFile(backupPropertiesDelegate.getBackupDirPath().toString());
//
//                // The mainBackupProperties hold properties that were backed up the last time the
//                // application was configured. On disk, the file will look like a smaller version of
//                // metacat.properties. It is located in the data storage directory, remote from the
//                // application install directories.
//                String MAIN_BACKUP_FILE_NAME = "metacat.properties.backup";
//                backupPropertiesDelegate.mainBackupFilePath =
//                    Paths.get(backupPropertiesDelegate.getBackupDirPath().toString(),
//                        MAIN_BACKUP_FILE_NAME).toString();
//
//                mainBackupProperties = new SortedProperties(
//                    backupPropertiesDelegate.mainBackupFilePath);
//                mainBackupProperties.load();
//            } else {
//                // if backupPath is still null, can't write the backup properties. The system will
//                // need to prompt the user for the properties and reconfigure
//                logMetacat.error("Backup Path not available; can't write the backup properties: "
//                    + backupPropertiesDelegate.getBackupDirPath());
//            }
        } catch (TransformerException e) {
            logAndThrow("PropertiesWrapper.initialize(): problem loading PropertiesMetaData (from "
                + mainMetadataFilePath + ")", e);
        } catch (IOException e) {
            logAndThrow("PropertiesWrapper.initialize(): I/O problem while loading properties: ",
                e);
        } catch (MetacatUtilException e) {
            logAndThrow("PropertiesWrapper.initialize(): SystemUtil.discoverExternalDir()",
                e);        }
    }

    private void initSitePropertiesFilePath() throws GeneralPropertyException, IOException {
        if (isNotBlankPath(sitePropertiesFilePath)) {
            logMetacat.debug("PropertiesWrapper.initSitePropertiesFilePath(): already set: "
            + sitePropertiesFilePath);
            if (!Files.exists(sitePropertiesFilePath)) {
                logAndThrow(
                    "PropertiesWrapper.initialize(): non-existent Site Properties File Path: "
                        + sitePropertiesFilePath, null);
            }
        } else {
            sitePropertiesFilePath =
                Paths.get(getProperty("application.sitePropertiesDir"), "metacat-site.properties");

            if (!Files.exists(sitePropertiesFilePath)) {
                Files.createDirectories(
                    sitePropertiesFilePath.getParent()); // no-op if already existing
                Files.createFile(sitePropertiesFilePath); // performs atomic check-!exists-&-create
                logMetacat.info(
                    "PropertiesWrapper.initialize(): no site properties file found; created: "
                        + sitePropertiesFilePath);
            }
        }
    }

    /**
     * Provides a string representation of all the properties seen by the application at runtime,
     * in the format:
     * <ul>
     * <li>key1=property1</li>
     * <li>key2=property2</li>
     * <li>key3=property3</li>
     * </ul>
     * ...etc. If a problem is encountered when retrieving any of the properties, the output for
     * that key will be in the form:
     * (keyname)=EXCEPTION getting this property: (exception message)
     *
     * @return string representation of all the properties seen by the application at runtime
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (String key : getPropertyNames()) {
            try {
                sb.append(key).append("=").append(getProperty(key)).append("\n");
            } catch (PropertyNotFoundException e) {
                sb.append(key).append("=").append("EXCEPTION getting this property: ")
                    .append(e.getMessage()).append("\n");
            }
        }
        return sb.toString();
    }

    private static void logAndThrow(String message, Exception e) throws GeneralPropertyException {
        String excepMsg = (e == null) ? "" : " : " + e.getMessage();
        GeneralPropertyException gpe = new GeneralPropertyException(message + excepMsg);
        gpe.fillInStackTrace();
        logMetacat.error(message, (e == null) ? gpe : e);
        throw gpe;
    }

    /**
     * store the properties to file. NOTE that this will persist only the properties that are: (a)
     * not included in mainDefaultProperties, or (b) have changed from the values in
     * mainDefaultProperties. From the <a
     * href="https://docs.oracle.com/javase/8/docs/api/java/util/Properties.html">
     * java.util.Properties javadoc</a>: "Properties from the defaults table of this Properties
     * table (if any) are not written out by this method." Also, Properties class is thread-safe -
     * no need to synchronize.
     */
    private void store() throws GeneralPropertyException {
        try (Writer output = new FileWriter(sitePropertiesFilePath.toFile())) {
            mainProperties.store(output, null);
        } catch (IOException e) {
            logAndThrow("I/O exception trying to call mainProperties.store(): ", e);
        }
    }

    private boolean isNotBlankPath(Path path) {
        return path != null && !path.toString().trim().equals("");
    }
}
