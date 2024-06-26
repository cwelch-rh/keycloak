/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.adapters;

import org.jboss.logging.Logger;
import org.keycloak.AuthorizationContext;
import org.keycloak.KeycloakSecurityContext;
import org.keycloak.adapters.rotation.AdapterTokenVerifier;
import org.keycloak.common.VerificationException;
import org.keycloak.common.util.Time;
import org.keycloak.representations.AccessToken;
import org.keycloak.representations.AccessTokenResponse;
import org.keycloak.representations.IDToken;

import java.io.IOException;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
public class RefreshableKeycloakSecurityContext extends KeycloakSecurityContext {

    protected static Logger log = Logger.getLogger(RefreshableKeycloakSecurityContext.class);

    protected transient KeycloakDeployment deployment;
    protected String refreshToken;

    public RefreshableKeycloakSecurityContext() {
    }

    public RefreshableKeycloakSecurityContext(KeycloakDeployment deployment, String tokenString, AccessToken token, String idTokenString, IDToken idToken, String refreshToken) {
        super(tokenString, token, idTokenString, idToken);
        this.deployment = deployment;
        this.refreshToken = refreshToken;
    }

    @Override
    public AccessToken getToken() {
        refreshExpiredToken(true);
        return super.getToken();
    }

    @Override
    public String getTokenString() {
        refreshExpiredToken(true);
        return super.getTokenString();
    }

    @Override
    public IDToken getIdToken() {
        refreshExpiredToken(true);
        return super.getIdToken();
    }

    @Override
    public String getIdTokenString() {
        refreshExpiredToken(true);
        return super.getIdTokenString();
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void logout(KeycloakDeployment deployment) {
        try {
            ServerRequest.invokeLogout(deployment, refreshToken);
        } catch (Exception e) {
            log.error("failed to invoke remote logout", e);
        }
    }

    public boolean isActive() {
        return token != null && this.token.isActive() && deployment!=null && this.token.getIat() >= deployment.getNotBefore();
    }

    public boolean isTokenTimeToLiveSufficient(AccessToken token) {
        return token != null && (token.getExp() - this.deployment.getTokenMinimumTimeToLive()) > Time.currentTime();
    }

    public KeycloakDeployment getDeployment() {
        return deployment;
    }

    public void setCurrentRequestInfo(KeycloakDeployment deployment) {
        this.deployment = deployment;
    }

    /**
     * @param checkActive if true, then we won't send refresh request if current accessToken is still active.
     * @return true if accessToken is active or was successfully refreshed
     */
    public boolean refreshExpiredToken(boolean checkActive) {
        if (checkActive) {
            if (log.isTraceEnabled()) {
                log.trace("checking whether to refresh.");
            }
            if (isActive() && isTokenTimeToLiveSufficient(this.token)) return true;
        }

        if (this.deployment == null || refreshToken == null) return false; // Might be serialized in HttpSession?

        if (!this.getRealm().equals(this.deployment.getRealm())) {
            // this should not happen, but let's check it anyway
            return false;
        }

        if (log.isTraceEnabled()) {
            log.trace("Doing refresh");
        }
        
        // block requests if the refresh token herein stored is already being used to refresh the token so that subsequent requests
        // can use the last refresh token issued by the server. Note that this will only work for deployments using the session store
        // and, when running in a cluster, sticky sessions must be used.
        // 
        synchronized (this) {
            if (checkActive) {
                log.trace("Checking whether token has been refreshed in another thread already.");
                if (isActive() && isTokenTimeToLiveSufficient(this.token)) return true;
            }
            AccessTokenResponse response;
            try {
                response = ServerRequest.invokeRefresh(deployment, refreshToken);
            } catch (IOException e) {
                log.error("Refresh token failure", e);
                return false;
            } catch (ServerRequest.HttpFailure httpFailure) {
                final Logger.Level logLevel = httpFailure.getError().contains("Refresh token expired") ? Logger.Level.WARN : Logger.Level.ERROR;
                log.log(logLevel, "Refresh token failure status: " + httpFailure.getStatus() + " " + httpFailure.getError());
                return false;
            }
            if (log.isTraceEnabled()) {
                log.trace("received refresh response");
            }
            String tokenString = response.getToken();
            AccessToken token = null;
            IDToken idToken = null;
            try {
                AdapterTokenVerifier.VerifiedTokens tokens = AdapterTokenVerifier.verifyTokens(tokenString, response.getIdToken(), deployment);
                token = tokens.getAccessToken();
                idToken = tokens.getIdToken();
                log.debug("Token Verification succeeded!");
            } catch (VerificationException e) {
                log.error("failed verification of token");
                return false;
            }
            // If the TTL is greater-or-equal to the expire time on the refreshed token, have to abort or go into an infinite refresh loop
            if (!isTokenTimeToLiveSufficient(token)) {
                log.error("failed to refresh the token with a longer time-to-live than the minimum");
                return false;
            }
            if (response.getNotBeforePolicy() > deployment.getNotBefore()) {
                deployment.updateNotBefore(response.getNotBeforePolicy());
            }
            if (idToken != null) {
                this.idToken = idToken;
                this.idTokenString = response.getIdToken();
            }
            this.token = token;
            if (response.getRefreshToken() != null) {
                if (log.isTraceEnabled()) {
                    log.trace("Setup new refresh token to the security context");
                }
                this.refreshToken = response.getRefreshToken();
            }
            this.tokenString = tokenString;
        }

        return true;
    }

    public void setAuthorizationContext(AuthorizationContext authorizationContext) {
        this.authorizationContext = authorizationContext;
    }
}
