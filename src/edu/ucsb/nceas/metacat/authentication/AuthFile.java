/**
 *  '$RCSfile$'
 *  Copyright: 2013 Regents of the University of California and the
 *             National Center for Ecological Analysis and Synthesis
 *
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package edu.ucsb.nceas.metacat.authentication;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.ConnectException;
import java.security.GeneralSecurityException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Random;
import java.util.Vector;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import edu.ucsb.nceas.metacat.AuthInterface;
import edu.ucsb.nceas.metacat.AuthLdap;
import edu.ucsb.nceas.metacat.properties.PropertyService;
import edu.ucsb.nceas.metacat.util.SystemUtil;
import edu.ucsb.nceas.utilities.PropertyNotFoundException;

/**
 * This an authentication class base on a username/password file.
 * It is an alternative authentication mechanism of the ldap authentication.
 * This is a singleton class and the password file looks like:
 *<?xml version="1.0" encoding="UTF-8" ?>
 * <subjects>
 *  <users>
 *      <user dn="uid=tao,o=NCEAS,dc=ecoinformatics,dc=org">
 *          <password>*******</password>
 *          <email>foo@foo.com</email>
 *          <surName>Smith</surName>
 *          <givenName>John</givenName>
 *          <group>nceas-dev</group>
 *      </user>
 *  </users>
 *  <groups>
 *    <group name="nceas-dev">
 *        <description>developers at NCEAS</description>
 *    </group>
 *  </groups>
 * </subjects>
 * http://commons.apache.org/proper/commons-configuration/userguide/howto_xml.html
 * @author tao
 *
 */
public class AuthFile implements AuthInterface {
    private static final String ORGANIZATION = "UNkown";
    private static final String NAME = "name";
    private static final String DN = "dn";
    private static final String DESCRIPTION = "description";
    private static final String PASSWORD = "password";
    private static final String SLASH = "/";
    private static final String AT = "@";
    private static final String SUBJECTS = "subjects";
    private static final String USERS = "users";
    private static final String USER = "user";
    private static final String GROUPS = "groups";
    private static final String GROUP = "group";
    private static final String EMAIL = "email";
    private static final String SURNAME = "surName";
    private static final String GIVENNAME = "givenName";
    private static final String INITCONTENT = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n"+
                                    "<"+SUBJECTS+">\n"+"<"+USERS+">\n"+"</"+USERS+">\n"+"<"+GROUPS+">\n"+"</"+GROUPS+">\n"+"</"+SUBJECTS+">\n";
    
    private static final byte[] SALT = {
        (byte) 0xde, (byte) 0x33, (byte) 0x10, (byte) 0x12,
        (byte) 0xde, (byte) 0x33, (byte) 0x10, (byte) 0x12,
    };
    private static Log log = LogFactory.getLog(AuthFile.class);
    private static AuthFile authFile = null;
    private static XMLConfiguration userpassword = null;
    private String authURI = null;
    private static String passwordFilePath = null;
    private static AuthFileHashInterface hashClass = null;
    /**
     * Get the instance of the AuthFile
     * @return
     * @throws AuthenticationException
     */
    public static AuthFile getInstance() throws AuthenticationException {
        if(authFile == null) {
            authFile = new AuthFile();
        }
        return authFile;
    }
    
    /**
     * Get the instance of the AuthFile from specified password file
     * @return
     * @throws AuthenticationException
     */
    public static AuthFile getInstance(String passwordFile) throws AuthenticationException {
        passwordFilePath = passwordFile;
        if(authFile == null) {
            authFile = new AuthFile();
        }
        return authFile;
    }
    
    /**
     * Constructor
     */
    private AuthFile() throws AuthenticationException {
        try {
            init();
        } catch (Exception e) {
            throw new AuthenticationException(e.getMessage());
        }
        
    }
    
    /*
     * Initialize the user/password configuration
     */
    private void init() throws PropertyNotFoundException, IOException, ConfigurationException, ClassNotFoundException, InstantiationException, IllegalAccessException {
        if(passwordFilePath == null) {
            passwordFilePath  = PropertyService.getProperty("auth.file.path");
        }
        File passwordFile = new File(passwordFilePath);
        
        authURI = SystemUtil.getContextURL();
        String hashClassName = PropertyService.getProperty("auth.file.hashClassName");
        Class classDefinition = Class.forName(hashClassName);
        Object object = classDefinition.newInstance();
        hashClass = (AuthFileHashInterface) object;
        
        //if the password file doesn't exist, create a new one and set the initial content
        if(!passwordFile.exists()) {
            passwordFile.createNewFile();
            OutputStreamWriter writer = null;
            FileOutputStream output = null;
            try {
              output = new FileOutputStream(passwordFile);
              writer = new OutputStreamWriter(output, "UTF-8");
              writer.write(INITCONTENT);
            } finally {
              writer.close();
              output.close();
            }
          }
          userpassword = new XMLConfiguration(passwordFile);
          userpassword.setExpressionEngine(new XPathExpressionEngine());
          userpassword.setAutoSave(true);
          userpassword.setDelimiterParsingDisabled(true);
          userpassword.setAttributeSplittingDisabled(true);
    }
    
    @Override
    public boolean authenticate(String user, String password)
                    throws AuthenticationException {
        boolean match = false;
        String passwordRecord = userpassword.getString(USERS+SLASH+USER+"["+AT+DN+"='"+user+"']"+SLASH+PASSWORD);
        if(passwordRecord != null) {
            try {
                match = hashClass.match(password, passwordRecord);
            } catch (Exception e) {
                throw new AuthenticationException(e.getMessage());
            }
            
        }
        return match;
    }
    
    @Override
    /**
     * Get all users. This is two-dimmention array. Each row is a user. The first element of
     * a row is the user name. The second element is common name. The third one is the organization name (null).
     * The fourth one is the organization unit name (null). The fifth one is the email address.
     */
    public String[][] getUsers(String user, String password)
                    throws ConnectException {
        List<Object> users = userpassword.getList(USERS+SLASH+USER+SLASH+AT+DN);
        if(users != null && users.size() > 0) {
            String[][] usersArray = new String[users.size()][5];
            for(int i=0; i<users.size(); i++) {
                User aUser = new User();
                String dn = (String)users.get(i);
                aUser.setDN(dn);
                usersArray[i][0] = dn; //dn
                String surname = null;
                List<Object> surNames = userpassword.getList(USERS+SLASH+USER+"["+AT+DN+"='"+dn+"']"+SLASH+SURNAME);
                if(surNames != null && !surNames.isEmpty()) {
                    surname = (String)surNames.get(0);
                }
                aUser.setSurName(surname);
                String givenName = null;
                List<Object> givenNames = userpassword.getList(USERS+SLASH+USER+"["+AT+DN+"='"+dn+"']"+SLASH+GIVENNAME);
                if(givenNames != null && !givenNames.isEmpty()) {
                    givenName = (String)givenNames.get(0);
                }
                aUser.setGivenName(givenName);
                usersArray[i][1] = aUser.getCn();//common name
                usersArray[i][2] = null;//organization name. We set null
                usersArray[i][3] = null;//organization ou name. We set null.
                List<Object> emails = userpassword.getList(USERS+SLASH+USER+"["+AT+DN+"='"+dn+"']"+SLASH+EMAIL);
                String email = null;
                if(emails != null && !emails.isEmpty() ) {
                    email = (String)emails.get(0);
                }
                usersArray[i][4] = email;
               
            }
            return usersArray;
        }
        return null;
    }
    
    @Override
    public String[] getUserInfo(String user, String password)
                    throws ConnectException {
        // TODO Auto-generated method stub
        return null;
    }
    
    
    @Override
    /**
     * Get the users for a particular group from the authentication service
     * The null will return if there is no user.
     * @param user
     *            the user for authenticating against the service
     * @param password
     *            the password for authenticating against the service
     * @param group
     *            the group whose user list should be returned
     * @returns string array of the user names belonging to the group
     */
    public String[] getUsers(String user, String password, String group)
                    throws ConnectException {
        List<Object> users = userpassword.getList(USERS+SLASH+USER+"["+GROUP+"='"+group+"']"+SLASH+AT+DN);
        if(users != null && users.size() > 0) {
            String[] usersArray = new String[users.size()];
            for(int i=0; i<users.size(); i++) {
                usersArray[i] = (String) users.get(i);
            }
            return usersArray;
        }
        return null;
    }
    
    @Override
    /**
     * Get all groups from the authentication service. It returns a two dimmension array. Each row is a
     * group. The first column is the group name. The second column is the description. The null will return if no group found.
     */
    public String[][] getGroups(String user, String password)
                    throws ConnectException {
        List<Object> groups = userpassword.getList(GROUPS+SLASH+GROUP+SLASH+AT+NAME);
        if(groups!= null && groups.size() >0) {
            String[][] groupsArray = new String[groups.size()][2];
            for(int i=0; i<groups.size(); i++) {
                String groupName = (String) groups.get(i);
                groupsArray[i][0] = groupName;
                String description = null;
                List<Object>descriptions = userpassword.getList(GROUPS+SLASH+GROUP+"["+AT+NAME+"='"+groupName+"']"+SLASH+DESCRIPTION);
                if(descriptions != null && !descriptions.isEmpty()) {
                    description = (String)descriptions.get(0);
                }
                groupsArray[i][1] = description; 
            }
            return groupsArray;
        }
        return null;
    }
    
    @Override
    /**
     * Get groups from a specified user. It returns two dimmension array. Each row is a
     * group. The first column is the group name. The null will return if no group found.
     */
    public String[][] getGroups(String user, String password, String foruser)
                    throws ConnectException {
        List<Object> groups = userpassword.getList(USERS+SLASH+USER+"["+AT+DN+"='"+foruser+"']"+SLASH+GROUP);
        if(groups != null && groups.size() > 0) {
            String[][] groupsArray = new String[groups.size()][2];
            for(int i=0; i<groups.size(); i++) {
                String groupName = (String) groups.get(i);
                groupsArray[i][0] = groupName;
                String description = null;
                List<Object>descriptions = userpassword.getList(GROUPS+SLASH+GROUP+"["+AT+NAME+"='"+groupName+"']"+SLASH+DESCRIPTION);
                if(descriptions != null && !descriptions.isEmpty()) {
                    description = (String)descriptions.get(0);
                }
                groupsArray[i][1] = description; 
            }
            return groupsArray;
        }
        return null;
    }
    
    @Override
    public HashMap<String, Vector<String>> getAttributes(String foruser)
                    throws ConnectException {
        // TODO Auto-generated method stub
        return null;
    }
    
    @Override
    public HashMap<String, Vector<String>> getAttributes(String user,
                    String password, String foruser) throws ConnectException {
        // TODO Auto-generated method stub
        return null;
    }
    
    @Override
    public String getPrincipals(String user, String password)
                    throws ConnectException {
            StringBuffer out = new StringBuffer();

            out.append("<?xml version=\"1.0\" encoding=\"iso-8859-1\"?>\n");
            out.append("<principals>\n");
            out.append("  <authSystem URI=\"" +authURI
                    + "\" organization=\"" + ORGANIZATION + "\">\n");

            // get all groups for directory context
            String[][] groups = getGroups(user, password);
            String[][] users = getUsers(user, password);
            int userIndex = 0;

            // for the groups and users that belong to them
            if (groups != null && users != null && groups.length > 0) {
                for (int i = 0; i < groups.length; i++) {
                    out.append("    <group>\n");
                    out.append("      <groupname>" + groups[i][0] + "</groupname>\n");
                    if(groups[i].length > 1) {
                        out.append("      <description>" + groups[i][1] + "</description>\n");
                    }
                    String[] usersForGroup = getUsers(user, password, groups[i][0]);
                    if(usersForGroup != null) {
                        for (int j = 0; j < usersForGroup.length; j++) {
                            userIndex = AuthLdap.searchUser(usersForGroup[j], users);
                            out.append("      <user>\n");

                            if (userIndex < 0) {
                                out.append("        <username>" + usersForGroup[j]
                                        + "</username>\n");
                            } else {
                                out.append("        <username>" + users[userIndex][0]
                                        + "</username>\n");
                                if(users[userIndex].length >=2) {
                                    out.append("        <name>" + users[userIndex][1]
                                                    + "</name>\n");
                                }
                                if(users[userIndex].length >=3) {
                                    out.append("        <email>" + users[userIndex][2]
                                                    + "</email>\n");
                                }
                               
                            }

                            out.append("      </user>\n");
                        }
                    }
                   
                    out.append("    </group>\n");
                }
            }

            if (users != null) {
                // for the users not belonging to any grou8p
                for (int j = 0; j < users.length; j++) {
                    out.append("    <user>\n");
                    out.append("      <username>" + users[j][0] + "</username>\n");
                    if(users[userIndex].length >=2) {
                        out.append("      <name>" + users[j][1] + "</name>\n");
                    }
                    if(users[userIndex].length >=3) {
                        out.append("      <email>" + users[j][2] + "</email>\n");
                    }
                   
                    out.append("    </user>\n");
                }
            }

            out.append("  </authSystem>\n");
        
        out.append("</principals>");
        return out.toString();
    }
    
    /**
     * Add a user to the file
     * @param userName the name of the user
     * @param groups  the groups the user belong to. The group should exist in the file
     * @param password  the password of the user
     */
    public void addUser(String dn, String[] groups, String plainPass, String hashedPass, String email, String surName, String givenName) throws AuthenticationException{
       User user = new User();
       user.setDN(dn);
       user.setGroups(groups);
       user.setPlainPass(plainPass);
       user.setHashedPass(hashedPass);
       user.setEmail(email);
       user.setSurName(surName);
       user.setGivenName(givenName);
       user.serialize();
    }
    
    /**
     * Add a group into the file
     * @param groupName the name of group
     */
    public void addGroup(String groupName, String description) throws AuthenticationException{
        if(groupName == null || groupName.trim().equals("")) {
            throw new AuthenticationException("AuthFile.addGroup - can't add a group whose name is null or blank.");
        }
        if(!groupExists(groupName)) {
            if(userpassword != null) {
              userpassword.addProperty(GROUPS+" "+GROUP+AT+NAME, groupName);
              if(description != null && !description.trim().equals("")) {
                  userpassword.addProperty(GROUPS+SLASH+GROUP+"["+AT+NAME+"='"+groupName+"']"+" "+DESCRIPTION, description);
              }
              //userpassword.reload();
             }
        } else {
            throw new AuthenticationException("AuthFile.addGroup - can't add the group "+groupName+" since it already exists.");
        }
    }
    
   
    
    /**
     * Change the password of the user to the new one which is hashed
     * @param usrName the specified user.   
     * @param newPassword the new password which will be set
     */
    public void modifyPassWithHash(String userName, String newHashPassword) throws AuthenticationException {
       User user = new User();
       user.setDN(userName);
       user.modifyHashPass(newHashPassword);
    }
    
    /**
     * Change the password of the user to the new one which is plain. However, only the hashed version will be serialized.
     * @param usrName the specified user.   
     * @param newPassword the new password which will be set
     */
    public void modifyPassWithPlain(String userName, String newPlainPassword) throws AuthenticationException {
        User user = new User();
        user.setDN(userName);
        user.modifyPlainPass(newPlainPassword);
    }
    
    
    /**
     * Add a user to a group
     * @param userName  the name of the user. the user should already exist
     * @param group  the name of the group. the group should already exist
     */
    public void addUserToGroup(String userName, String group) throws AuthenticationException {
        User user = new User();
        user.setDN(userName);
        user.addToGroup(group);
    }
    
    /**
     * Remove a user from a group.
     * @param userName  the name of the user. the user should already exist.
     * @param group the name of the group
     */
    public void removeUserFromGroup(String userName, String group) throws AuthenticationException{
        User user = new User();
        user.setDN(userName);
        user.removeFromGroup(group);
    }
    
  
    
    /**
     * If the specified user name exist or not
     * @param userName the name of the user
     * @return true if the user eixsit
     */
    private synchronized boolean userExists(String userName) throws AuthenticationException{
        if(userName == null || userName.trim().equals("")) {
            throw new AuthenticationException("AuthFile.userExist - can't judge if a user exists when its name is null or blank.");
        }
        List<Object> users = userpassword.getList(USERS+SLASH+USER+SLASH+AT+DN);
        if(users != null && users.contains(userName)) {
            return true;
        } else {
            return false;
        }
    }
    
    /**
     * If the specified group exist or not
     * @param groupName the name of the group
     * @return true if the user exists
     */
    private synchronized boolean groupExists(String groupName) throws AuthenticationException{
        if(groupName == null || groupName.trim().equals("")) {
            throw new AuthenticationException("AuthFile.groupExist - can't judge if a group exists when its name is null or blank.");
        }
        List<Object> groups = userpassword.getList(GROUPS+SLASH+GROUP+SLASH+AT+NAME);
        if(groups != null && groups.contains(groupName)) {
            return true;
        } else {
            return false;
        }
    }
    
    /*
     * Encrypt a plain text
     */
    private static String encrypt(String plain)  {
      return hashClass.hash(plain);
    }
    

    
    /**
     * An class represents the information for a user. 
     * @author tao
     *
     */
    private class User {
        private String dn = null;//the distinguish name
        private String plainPass = null;
        private String hashedPass = null;
        private String email = null;
        private String surName = null;
        private String givenName = null;
        private String cn = null;//the common name
        private String[] groups = null;
        
        /**
         * Get the distinguish name of the user
         * @return the distinguish name 
         */
        public String getDN() {
            return this.dn;
        }
        
        /**
         * Set the distinguish name for the user
         * @param dn the specified dn
         */
        public void setDN(String dn) {
            this.dn = dn;
        }
        
        /**
         * Get the plain password for the user. This value will NOT be serialized to
         * the password file
         * @return the plain password for the user
         */
        public String getPlainPass() {
            return plainPass;
        }
        
        /**
         * Set the plain password for the user.
         * @param plainPass the plain password will be set.
         */
        public void setPlainPass(String plainPass) {
            this.plainPass = plainPass;
        }
        
        /**
         * Get the hashed password of the user
         * @return the hashed password of the user
         */
        public String getHashedPass() {
            return hashedPass;
        }
        
        /**
         * Set the hashed the password for the user.
         * @param hashedPass the hashed password will be set.
         */
        public void setHashedPass(String hashedPass) {
            this.hashedPass = hashedPass;
        }
        
        /**
         * Get the email of the user
         * @return the email of the user
         */
        public String getEmail() {
            return email;
        }
        
        /**
         * Set the email address for the user
         * @param email the eamil address will be set
         */
        public void setEmail(String email) {
            this.email = email;
        }
        
        /**
         * Get the surname of the user
         * @return the surname of the user
         */
        public String getSurName() {
            return surName;
        }
        
        /**
         * Set the surname of the user
         * @param surName
         */
        public void setSurName(String surName) {
            this.surName = surName;
        }
        
        /**
         * Get the given name of the user
         * @return the given name of the user
         */
        public String getGivenName() {
            return givenName;
        }
        
        /**
         * Set the GivenName of the user
         * @param givenName
         */
        public void setGivenName(String givenName) {
            this.givenName = givenName;
        }
        
        /**
         * Get the common name of the user. If the cn is null, the GivenName +SurName will
         * be returned
         * @return the common name
         */
        public String getCn() {
            if(cn != null) {
                return cn;
            } else {
                if (givenName != null && surName != null) {
                    return givenName+" "+surName;
                } else if (givenName != null) {
                    return givenName;
                } else if (surName != null ) {
                    return surName;
                } else {
                    return null;
                }
            }
        }
        
        /**
         * Set the common name for the user
         * @param cn
         */
        public void setCn(String cn) {
            this.cn = cn;
        }
        
        /**
         * Get the groups of the user belong to
         * @return
         */
        public String[] getGroups() {
            return groups;
        }
        
        /**
         * Set the groups of the user belong to
         * @param groups
         */
        public void setGroups(String[] groups) {
            this.groups = groups;
        }
        
        /**
         * Add the user to a group and serialize the change to the password file.
         * @param group the group which the user will join
         * @throws AuthenticationException 
         */
        public void addToGroup(String group) throws AuthenticationException {
            if(group == null || group.trim().equals("")) {
                throw new IllegalArgumentException("AuthFile.User.addToGroup - the group can't be null or blank");
            }
            if(!userExists(dn)) {
                throw new AuthenticationException("AuthFile.User.addUserToGroup - the user "+dn+ " doesn't exist.");
            }
            if(!groupExists(group)) {
                throw new AuthenticationException("AuthFile.User.addUserToGroup - the group "+group+ " doesn't exist.");
            }
            List<Object> existingGroups = userpassword.getList(USERS+SLASH+USER+"["+AT+DN+"='"+dn+"']"+SLASH+GROUP);
            if(existingGroups != null && existingGroups.contains(group)) {
                throw new AuthenticationException("AuthFile.User.addUserToGroup - the user "+dn+ " already is the memember of the group "+group);
            }
            userpassword.addProperty(USERS+SLASH+USER+"["+AT+DN+"='"+dn+"']"+" "+GROUP, group);
            //add information to the memory
            if(groups == null) {
                if(existingGroups == null || existingGroups.isEmpty()) {
                    groups = new String[1];
                    groups[0] = group;
                } else {
                    groups = new String[existingGroups.size()+1];
                    for(int i=0; i<existingGroups.size(); i++) {
                        groups[i] = (String)existingGroups.get(i);
                    }
                    groups[existingGroups.size()] = group;
                }
                
            } else {
                String[] oldGroups = groups;
                groups = new String[oldGroups.length+1];
                for(int i=0; i<oldGroups.length; i++) {
                    groups[i]= oldGroups[i];
                }
                groups[oldGroups.length] = group;
                
            }
        }
        
        /**
         * Remove the user from a group and serialize the change to the password file
         * @param group
         * @throws AuthenticationException
         */
        public void removeFromGroup(String group) throws AuthenticationException {
            if(!userExists(dn)) {
                throw new AuthenticationException("AuthFile.User.removeUserFromGroup - the user "+dn+ " doesn't exist.");
            }
            if(!groupExists(group)) {
                throw new AuthenticationException("AuthFile.User.removeUserFromGroup - the group "+group+ " doesn't exist.");
            }
            String key = USERS+SLASH+USER+"["+AT+DN+"='"+dn+"']"+SLASH+GROUP;
            List<Object> existingGroups = userpassword.getList(key);
            if(!existingGroups.contains(group)) {
                throw new AuthenticationException("AuthFile.User.removeUserFromGroup - the user "+dn+ " isn't the memember of the group "+group);
            } else {
                userpassword.clearProperty(key+"[.='"+group+"']");
            }
            //change the value in the memory.
            if(groups != null) {
                boolean contains = false;
                for(int i=0; i<groups.length; i++) {
                    if(groups[i].equals(group)) {
                        contains = true;
                        break;
                    }
                }
                String[] newGroups = new String[groups.length-1];
                int k =0;
                for(int i=0; i<groups.length; i++) {
                    if(!groups[i].equals(group)) {
                       newGroups[k] = groups[i];
                       k++;
                    }
                }
                groups = newGroups;
            }
        }
        
        /**
         * Modify the hash password and serialize it to the password file
         * @param hashPass
         * @throws AuthenticationException
         */
        public void modifyHashPass(String hashPass) throws AuthenticationException {
            if(hashPass == null || hashPass.trim().equals("")) {
                throw new AuthenticationException("AuthFile.User.modifyHashPass - can't change the password to the null or blank.");
            }
            if(!userExists(dn)) {
                throw new AuthenticationException("AuthFile.User.modifyHashPass - can't change the password for the user "+dn+" since it doesn't eixt.");
            }
            userpassword.setProperty(USERS+SLASH+USER+"["+AT+DN+"='"+dn+"']"+SLASH+PASSWORD, hashPass);
            setHashedPass(hashPass);
      
        }
        
        /**
         * Modify the plain password and serialize its hash version to the password file
         * @param plainPass
         * @throws AuthenticationException 
         */
        public void modifyPlainPass(String plainPass) throws AuthenticationException {
            if(plainPass == null || plainPass.trim().equals("")) {
                throw new AuthenticationException("AuthFile.User.modifyPlainPass - can't change the password to the null or blank.");
            }
            if(!userExists(dn)) {
                throw new AuthenticationException("AuthFile.User.modifyPlainPass - can't change the password for the user "+dn+" since it doesn't eixt.");
            }
            String hashPassword = null;
            try {
                hashPassword = encrypt(plainPass);
            } catch (Exception e) {
                throw new AuthenticationException("AuthFile.User.modifyPlainPass - can't encript the password since "+e.getMessage());
            }
            userpassword.setProperty(USERS+SLASH+USER+"["+AT+DN+"='"+dn+"']"+SLASH+PASSWORD, hashPassword);
            setPlainPass(plainPass);
        }
        
        /**
         * Add the user to the password file. 
         */
        public void serialize() throws AuthenticationException {
            if(dn == null || dn.trim().equals("")) {
                throw new AuthenticationException("AuthFile.User.serialize - can't add a user whose name is null or blank.");
            }
            if(hashedPass == null || hashedPass.trim().equals("")) {
                if(plainPass == null || plainPass.trim().equals("")) {
                    throw new AuthenticationException("AuthFile.User.serialize - can't add a user whose password is null or blank.");
                } else {
                    try {
                        hashedPass = encrypt(plainPass);
                    } catch (Exception e) {
                        throw new AuthenticationException("AuthFile.User.serialize - can't encript the password since "+e.getMessage());
                    }
                }
            }

            if(!userExists(dn)) {
                if(userpassword != null) {
                  userpassword.addProperty(USERS+" "+USER+AT+DN, dn);
                  userpassword.addProperty(USERS+SLASH+USER+"["+AT+DN+"='"+dn+"']"+" "+PASSWORD, hashedPass);
                  
                  if(email != null && !email.trim().equals("")) {
                      userpassword.addProperty(USERS+SLASH+USER+"["+AT+DN+"='"+dn+"']"+" "+EMAIL, email);
                  }
                  
                  if(surName != null && !surName.trim().equals("")) {
                      userpassword.addProperty(USERS+SLASH+USER+"["+AT+DN+"='"+dn+"']"+" "+SURNAME, surName);
                  }
                  
                  if(givenName != null && !givenName.trim().equals("")) {
                      userpassword.addProperty(USERS+SLASH+USER+"["+AT+DN+"='"+dn+"']"+" "+GIVENNAME, givenName);
                  }

                  if(groups != null) {
                      for(int i=0; i<groups.length; i++) {
                          String group = groups[i];
                          if(group != null && !group.trim().equals("")) {
                              if(groupExists(group)) {
                                  userpassword.addProperty(USERS+SLASH+USER+"["+AT+DN+"='"+dn+"']"+" "+GROUP, group);
                              }
                          }
                      }
                  }
                  //userpassword.reload();
                 }
            } else {
                throw new AuthenticationException("AuthFile.User.serialize - can't add the user "+dn+" since it already exists.");
            }
        }
    }

}
