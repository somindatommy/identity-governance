/*
 * Copyright (c) 2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.mgt.store;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.identity.base.IdentityException;
import org.wso2.carbon.identity.core.util.IdentityDatabaseUtil;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.identity.mgt.model.UserIdentityClaim;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.user.api.UserStoreManager;
import org.wso2.carbon.user.core.UserCoreConstants;
import org.wso2.carbon.user.core.util.UserCoreUtil;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * //TODO remove method when user is deleted
 */
public class JDBCIdentityDataStore extends InMemoryIdentityDataStore {

    private static Log log = LogFactory.getLog(JDBCIdentityDataStore.class);

    @Override
    public void store(UserIdentityClaim userIdentityDTO, UserStoreManager userStoreManager)
            throws IdentityException {

        if (userIdentityDTO == null || userIdentityDTO.getUserIdentityDataMap().isEmpty()) {
            return;
        }

        // Before putting to cache, has to check this whether this available in the database
        // Putting into cache

        String userName = userIdentityDTO.getUserName();
        String domainName = ((org.wso2.carbon.user.core.UserStoreManager) userStoreManager).getRealmConfiguration().
                getUserStoreProperty(UserCoreConstants.RealmConfig.PROPERTY_DOMAIN_NAME);
        userName = UserCoreUtil.addDomainToName(userName, domainName);
        userIdentityDTO.setUserName(userName);

        super.store(userIdentityDTO, userStoreManager);

        int tenantId = MultitenantConstants.SUPER_TENANT_ID;
        try {
            tenantId = userStoreManager.getTenantId();
        } catch (UserStoreException e) {
            log.error("Error while getting tenant Id.", e);
        }


        Map<String, String> data = userIdentityDTO.getUserIdentityDataMap();

        for (Map.Entry<String, String> entry : data.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            boolean isUserExists = false;
            try {
                isUserExists = isExistingUserDataValue(userName, tenantId, key);
            } catch (SQLException e) {
                throw IdentityException.error("Error occurred while checking if user existing", e);
            }
            try {
                if (isUserExists) {
                    updateUserDataValue(userName, tenantId, key, value);
                } else {
                    addUserDataValue(userName, tenantId, key, value);
                }
            } catch (SQLException e) {
                throw IdentityException.error("Error occurred while persisting user data", e);
            }
        }
    }

    private boolean isExistingUserDataValue(String userName, int tenantId, String key) throws SQLException {

        Connection connection = IdentityDatabaseUtil.getDBConnection();
        PreparedStatement prepStmt = null;
        ResultSet results;
        boolean isUsernameCaseSensitive = IdentityUtil.isUserStoreInUsernameCaseSensitive(userName, tenantId);
        try {
            String query;
            if (isUsernameCaseSensitive) {
                query = SQLQuery.CHECK_EXIST_USER_DATA;
            } else {
                query = SQLQuery.CHECK_EXIST_USER_DATA_CASE_INSENSITIVE;
            }
            prepStmt = connection.prepareStatement(query);
            prepStmt.setInt(1, tenantId);
            prepStmt.setString(2, userName);
            prepStmt.setString(3, key);
            results = prepStmt.executeQuery();
            if (results.next()) {
                return true;
            }
            connection.commit();
        } finally {
            IdentityDatabaseUtil.closeStatement(prepStmt);
            IdentityDatabaseUtil.closeConnection(connection);
        }
        return false;
    }


    private void addUserDataValue(String userName, int tenantId, String key, String value) throws SQLException {

        Connection connection = IdentityDatabaseUtil.getDBConnection();
        PreparedStatement prepStmt = null;

        try {
            prepStmt = connection.prepareStatement(SQLQuery.STORE_USER_DATA);
            prepStmt.setInt(1, tenantId);
            prepStmt.setString(2, userName);
            prepStmt.setString(3, key);
            prepStmt.setString(4, value);
            prepStmt.execute();
            connection.commit();
        } finally {
            IdentityDatabaseUtil.closeStatement(prepStmt);
            IdentityDatabaseUtil.closeConnection(connection);
        }
    }


    private void updateUserDataValue(String userName, int tenantId, String key, String value) throws SQLException {

        Connection connection = IdentityDatabaseUtil.getDBConnection();
        PreparedStatement prepStmt = null;
        boolean isUsernameCaseSensitive = IdentityUtil.isUserStoreInUsernameCaseSensitive(userName, tenantId);
        try {
            String query;
            if (isUsernameCaseSensitive) {
                query = SQLQuery.UPDATE_USER_DATA;
            } else {
                query = SQLQuery.UPDATE_USER_DATA_CASE_INSENSITIVE;
            }
            prepStmt = connection.prepareStatement(query);
            prepStmt.setString(1, value);
            prepStmt.setInt(2, tenantId);
            prepStmt.setString(3, userName);
            prepStmt.setString(4, key);
            prepStmt.executeUpdate();
            connection.commit();
        } finally {
            IdentityDatabaseUtil.closeStatement(prepStmt);
            IdentityDatabaseUtil.closeConnection(connection);
        }

    }

    @Override
    public UserIdentityClaim load(String userName, UserStoreManager userStoreManager) {

        String domainName = ((org.wso2.carbon.user.core.UserStoreManager) userStoreManager).getRealmConfiguration().
                getUserStoreProperty(UserCoreConstants.RealmConfig.PROPERTY_DOMAIN_NAME);
        userName = UserCoreUtil.addDomainToName(userName, domainName);

        // Getting from cache
        UserIdentityClaim dto = super.load(userName, userStoreManager);
        if (dto != null) {
            return dto;
        }

        Connection connection = IdentityDatabaseUtil.getDBConnection();
        PreparedStatement prepStmt = null;
        ResultSet results = null;
        try {
            int tenantId = userStoreManager.getTenantId();
            boolean isUsernameCaseSensitive = IdentityUtil.isUserStoreInUsernameCaseSensitive(userName, tenantId);
            String query;
            if (isUsernameCaseSensitive) {
                query = SQLQuery.LOAD_USER_DATA;
            } else {
                query = SQLQuery.LOAD_USER_DATA_CASE_INSENSITIVE;
            }
            prepStmt = connection.prepareStatement(query);
            prepStmt.setInt(1, tenantId);
            prepStmt.setString(2, userName);
            results = prepStmt.executeQuery();
            Map<String, String> data = new HashMap<String, String>();
            while (results.next()) {
                data.put(results.getString(1), results.getString(2));
            }
            connection.commit();
            if (log.isDebugEnabled()) {
                log.debug("Retrieved identity data for:" + tenantId + ":" + userName);
                for (Map.Entry<String, String> dataEntry : data.entrySet()) {
                    log.debug(dataEntry.getKey() + " : " + dataEntry.getValue());
                }
            }
            dto = new UserIdentityClaim(userName, data);
            dto.setTenantId(tenantId);
            try {
                super.store(dto, userStoreManager);
            } catch (IdentityException e) {
                log.error("Error while reading user identity data", e);
            }
            return dto;
        } catch (SQLException | UserStoreException e) {
            log.error("Error while reading user identity data", e);
        } finally {
            IdentityDatabaseUtil.closeResultSet(results);
            IdentityDatabaseUtil.closeStatement(prepStmt);
            IdentityDatabaseUtil.closeConnection(connection);
        }

        return null;
    }

    @Override
    public void remove(String userName, UserStoreManager userStoreManager) throws IdentityException {

        super.remove(userName, userStoreManager);
        String domainName = ((org.wso2.carbon.user.core.UserStoreManager) userStoreManager).
                getRealmConfiguration().getUserStoreProperty(UserCoreConstants.RealmConfig.PROPERTY_DOMAIN_NAME);
        userName = UserCoreUtil.addDomainToName(userName, domainName);
        Connection connection = IdentityDatabaseUtil.getDBConnection();
        PreparedStatement prepStmt = null;
        try {
            int tenantId = userStoreManager.getTenantId();
            boolean isUsernameCaseSensitive = IdentityUtil.isUserStoreInUsernameCaseSensitive(userName, tenantId);
            String query;
            if (isUsernameCaseSensitive) {
                query = SQLQuery.DELETE_USER_DATA;
            } else {
                query = SQLQuery.DELETE_USER_DATA_CASE_INSENSITIVE;
            }
            prepStmt = connection.prepareStatement(query);
            prepStmt.setInt(1, tenantId);
            prepStmt.setString(2, userName);
            prepStmt.execute();
            connection.commit();
        } catch (SQLException | UserStoreException e) {
            throw IdentityException.error("Error while reading user identity data", e);
        } finally {
            IdentityDatabaseUtil.closeStatement(prepStmt);
            IdentityDatabaseUtil.closeConnection(connection);
        }
    }

    /**
     * This class contains the SQL queries.
     * Schem:
     * ||TENANT_ID || USERR_NAME || DATA_KEY || DATA_VALUE ||
     * The primary key is tenantId, userName, DatKey combination
     */
    private static class SQLQuery {
        public static final String CHECK_EXIST_USER_DATA = "SELECT DATA_VALUE FROM IDN_IDENTITY_USER_DATA WHERE " +
                "TENANT_ID = ? AND USER_NAME = ? AND DATA_KEY = ?";
        public static final String CHECK_EXIST_USER_DATA_CASE_INSENSITIVE = "SELECT DATA_VALUE FROM " +
                "IDN_IDENTITY_USER_DATA WHERE TENANT_ID = ? AND LOWER(USER_NAME) = LOWER(?) AND DATA_KEY = ?";

        public static final String STORE_USER_DATA = "INSERT INTO IDN_IDENTITY_USER_DATA (TENANT_ID, USER_NAME, " +
                "DATA_KEY, DATA_VALUE) VALUES (?,?,?,?)";

        public static final String UPDATE_USER_DATA = "UPDATE IDN_IDENTITY_USER_DATA SET DATA_VALUE=? WHERE " +
                "TENANT_ID=? AND USER_NAME=? AND DATA_KEY=?";
        public static final String UPDATE_USER_DATA_CASE_INSENSITIVE = "UPDATE IDN_IDENTITY_USER_DATA SET " +
                "DATA_VALUE=? WHERE TENANT_ID=? AND LOWER(USER_NAME)=LOWER(?) AND DATA_KEY=?";

        public static final String LOAD_USER_DATA = "SELECT DATA_KEY, DATA_VALUE FROM IDN_IDENTITY_USER_DATA WHERE " +
                "TENANT_ID = ? AND USER_NAME = ?";
        public static final String LOAD_USER_DATA_CASE_INSENSITIVE = "SELECT " + "DATA_KEY, DATA_VALUE FROM " +
                "IDN_IDENTITY_USER_DATA WHERE TENANT_ID = ? AND LOWER(USER_NAME) = LOWER(?)";

        public static final String DELETE_USER_DATA = "DELETE FROM IDN_IDENTITY_USER_DATA WHERE " +
                "TENANT_ID = ? AND USER_NAME = ?";
        public static final String DELETE_USER_DATA_CASE_INSENSITIVE = "DELETE FROM IDN_IDENTITY_USER_DATA WHERE " +
                "TENANT_ID = ? AND LOWER(USER_NAME) = LOWER(?)";

        private SQLQuery() {
        }
    }
}