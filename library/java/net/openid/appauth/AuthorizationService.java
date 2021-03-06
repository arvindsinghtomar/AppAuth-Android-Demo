/*
 * Copyright 2015 The AppAuth for Android Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openid.appauth;

import static net.openid.appauth.Preconditions.checkNotNull;

import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.support.customtabs.CustomTabsIntent;

import net.openid.appauth.AuthorizationException.GeneralErrors;
import net.openid.appauth.AuthorizationException.RegistrationRequestErrors;
import net.openid.appauth.AuthorizationException.TokenRequestErrors;
import net.openid.appauth.browser.BrowserDescriptor;
import net.openid.appauth.browser.BrowserSelector;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.util.Calendar;
import java.util.Map;


/**
 * Dispatches requests to an OAuth2 authorization service. Note that instances of this class
 * <em>must be manually disposed</em> when no longer required, to avoid leaks
 * (see {@link #dispose()}.
 */
public class AuthorizationService {

    @VisibleForTesting
    Context mContext;

    @NonNull
    private final AppAuthConfiguration mClientConfiguration;

    @NonNull
    private final CustomTabManager mCustomTabManager;

    @Nullable
    private final BrowserDescriptor mBrowser;

    private boolean mDisposed = false;

    /**
     * Creates an AuthorizationService instance, using the
     * {@link AppAuthConfiguration#DEFAULT default configuration}. Note that
     * instances of this class must be manually disposed when no longer required, to avoid
     * leaks (see {@link #dispose()}.
     */
    public AuthorizationService(@NonNull Context context) {
        this(context, AppAuthConfiguration.DEFAULT);
    }

    /**
     * Creates an AuthorizationService instance, using the specified configuration. Note that
     * instances of this class must be manually disposed when no longer required, to avoid
     * leaks (see {@link #dispose()}.
     */
    public AuthorizationService(
            @NonNull Context context,
            @NonNull AppAuthConfiguration clientConfiguration) {
        this(context,
                clientConfiguration,
                BrowserSelector.select(
                        context,
                        clientConfiguration.getBrowserMatcher()),
                new CustomTabManager(context));
    }

    /**
     * Constructor that injects a url builder into the service for testing.
     */
    @VisibleForTesting
    AuthorizationService(@NonNull Context context,
                         @NonNull AppAuthConfiguration clientConfiguration,
                         @Nullable BrowserDescriptor browser,
                         @NonNull CustomTabManager customTabManager) {
        mContext = checkNotNull(context);
        mClientConfiguration = clientConfiguration;
        mCustomTabManager = customTabManager;
        mBrowser = browser;

        if (browser != null && browser.useCustomTab) {
            mCustomTabManager.bind(browser.packageName);
        }
    }

    /**
     * Creates a custom tab builder, that will use a tab session from an existing connection to
     * a web browser, if available.
     */
    public CustomTabsIntent.Builder createCustomTabsIntentBuilder() {
        checkNotDisposed();
        return mCustomTabManager.createCustomTabsIntentBuilder();
    }

    /**
     * Sends an authorization request to the authorization service, using a
     * <a href="https://developer.chrome.com/multidevice/android/customtabs">custom tab</a>
     * if available, or a browser instance.
     * The parameters of this request are determined by both the authorization service
     * configuration and the provided {@link AuthorizationRequest request object}. Upon completion
     * of this request, the provided {@link PendingIntent completion PendingIntent} will be invoked.
     * If the user cancels the authorization request, the current activity will regain control.
     */
    public void performAuthorizationRequest(
            @NonNull AuthorizationRequest request,
            @NonNull PendingIntent completedIntent) {
        performAuthorizationRequest(
                request,
                completedIntent,
                null,
                createCustomTabsIntentBuilder().build());
    }

    /**
     * Sends an authorization request to the authorization service, using a
     * <a href="https://developer.chrome.com/multidevice/android/customtabs">custom tab</a>
     * if available, or a browser instance.
     * The parameters of this request are determined by both the authorization service
     * configuration and the provided {@link AuthorizationRequest request object}. Upon completion
     * of this request, the provided {@link PendingIntent completion PendingIntent} will be invoked.
     * If the user cancels the authorization request, the provided
     * {@link PendingIntent cancel PendingIntent} will be invoked.
     */
    public void performAuthorizationRequest(
            @NonNull AuthorizationRequest request,
            @NonNull PendingIntent completedIntent,
            @NonNull PendingIntent canceledIntent) {
        performAuthorizationRequest(
                request,
                completedIntent,
                canceledIntent,
                createCustomTabsIntentBuilder().build());
    }

    /**
     * Sends an authorization request to the authorization service, using a
     * <a href="https://developer.chrome.com/multidevice/android/customtabs">custom tab</a>.
     * The parameters of this request are determined by both the authorization service
     * configuration and the provided {@link AuthorizationRequest request object}. Upon completion
     * of this request, the provided {@link PendingIntent completion PendingIntent} will be invoked.
     * If the user cancels the authorization request, the current activity will regain control.
     *
     * @param customTabsIntent The intent that will be used to start the custom tab. It is
     *                         recommended that this intent be created with the help of
     *                         {@link #createCustomTabsIntentBuilder()}, which will ensure
     *                         that a warmed-up version of the browser will be used,
     *                         minimizing latency.
     */
    public void performAuthorizationRequest(
            @NonNull AuthorizationRequest request,
            @NonNull PendingIntent completedIntent,
            @NonNull CustomTabsIntent customTabsIntent) {
        performAuthorizationRequest(
                request,
                completedIntent,
                null,
                customTabsIntent);
    }

    /**
     * Sends an authorization request to the authorization service, using a
     * <a href="https://developer.chrome.com/multidevice/android/customtabs">custom tab</a>.
     * The parameters of this request are determined by both the authorization service
     * configuration and the provided {@link AuthorizationRequest request object}. Upon completion
     * of this request, the provided {@link PendingIntent completion PendingIntent} will be invoked.
     * If the user cancels the authorization request, the provided
     * {@link PendingIntent cancel PendingIntent} will be invoked.
     *
     * @param customTabsIntent The intent that will be used to start the custom tab. It is
     *                         recommended that this intent be created with the help of
     *                         {@link #createCustomTabsIntentBuilder()}, which will ensure
     *                         that a warmed-up version of the browser will be used,
     *                         minimizing latency.
     * @throws android.content.ActivityNotFoundException if no suitable browser is available to
     *                                                   perform the authorization flow.
     */
    public void performAuthorizationRequest(
            @NonNull AuthorizationRequest request,
            @NonNull PendingIntent completedIntent,
            @Nullable PendingIntent canceledIntent,
            @NonNull CustomTabsIntent customTabsIntent) {
        checkNotDisposed();

        if (mBrowser == null) {
            throw new ActivityNotFoundException();
        }

        Uri requestUri = request.toUri();
        Intent intent;
        if (mBrowser.useCustomTab) {
            intent = customTabsIntent.intent;
        } else {
            intent = new Intent(Intent.ACTION_VIEW);
        }
        intent.setPackage(mBrowser.packageName);
        intent.setData(requestUri);

        Logger.debug("Using %s as browser for auth, custom tab = %s",
                intent.getPackage(),
                mBrowser.useCustomTab.toString());
        intent.putExtra(CustomTabsIntent.EXTRA_TITLE_VISIBILITY_STATE, CustomTabsIntent.NO_TITLE);

        Logger.debug("Initiating authorization request to %s",
                request.configuration.authorizationEndpoint);
        mContext.startActivity(AuthorizationManagementActivity.createStartIntent(
                mContext,
                request,
                intent,
                completedIntent,
                canceledIntent));
    }

    /**
     * Sends a request to the authorization service to exchange a code granted as part of an
     * authorization request for a token. The result of this request will be sent to the provided
     * callback handler.
     */
    public void performTokenRequest(
            @NonNull TokenRequest request,
            @NonNull TokenResponseCallback callback) {
        checkNotDisposed();
        Logger.debug("Initiating code exchange request to %s",
                request.configuration.tokenEndpoint);
        new TokenRequestTask(request, NoClientAuthentication.INSTANCE, callback).execute();
    }

    /**
     * Sends a request to the authorization service to exchange a code granted as part of an
     * authorization request for a token. The result of this request will be sent to the provided
     * callback handler.
     */
    public void performTokenRequest(
            @NonNull TokenRequest request,
            @NonNull ClientAuthentication clientAuthentication,
            @NonNull TokenResponseCallback callback) {
        checkNotDisposed();
        Logger.debug("Initiating code exchange request to %s",
                request.configuration.tokenEndpoint);
        new TokenRequestTask(request, clientAuthentication, callback)
                .execute();
    }

    /**
     * Performs ID token validation.
     */
    public void performTokenValidation(
            @NonNull TokenResponse response,
            @NonNull ClientAuthentication clientAuthentication,
            @NonNull TokenValidationResponseCallback callback) {
        checkNotDisposed();
        Logger.debug("Initiating code exchange request to %s",
                response.request.configuration.discoveryDoc.getValidateTokenEndpoint());
        new TokenValidationRequestTask(response, clientAuthentication, callback)
                .execute();
    }

    /**
     * Sends a request to the authorization service to dynamically register a client.
     * The result of this request will be sent to the provided callback handler.
     */
    public void performRegistrationRequest(
            @NonNull RegistrationRequest request,
            @NonNull RegistrationResponseCallback callback) {
        checkNotDisposed();
        Logger.debug("Initiating dynamic client registration %s",
                request.configuration.registrationEndpoint.toString());
        new RegistrationRequestTask(request, callback).execute();
    }

    /**
     * Disposes state that will not normally be handled by garbage collection. This should be
     * called when the authorization service is no longer required, including when any owning
     * activity is paused or destroyed (i.e. in {@link android.app.Activity#onStop()}).
     */
    public void dispose() {
        if (mDisposed) {
            return;
        }
        mCustomTabManager.unbind();
        mDisposed = true;
    }

    private void checkNotDisposed() {
        if (mDisposed) {
            throw new IllegalStateException("Service has been disposed and rendered inoperable");
        }
    }

    private class TokenRequestTask
            extends AsyncTask<Void, Void, JSONObject> {
        private TokenRequest mRequest;
        private TokenResponseCallback mCallback;
        private ClientAuthentication mClientAuthentication;

        private AuthorizationException mException;

        TokenRequestTask(TokenRequest request, @NonNull ClientAuthentication clientAuthentication,
                         TokenResponseCallback callback) {
            mRequest = request;
            mCallback = callback;
            mClientAuthentication = clientAuthentication;
        }

        @Override
        protected JSONObject doInBackground(Void... voids) {
            InputStream is = null;
            try {
                HttpURLConnection conn =
                        mClientConfiguration.getConnectionBuilder()
                                .openConnection(mRequest.configuration.tokenEndpoint);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                conn.setDoOutput(true);

                Map<String, String> headers = mClientAuthentication
                        .getRequestHeaders(mRequest.clientId);
                if (headers != null) {
                    for (Map.Entry<String, String> header : headers.entrySet()) {
                        conn.setRequestProperty(header.getKey(), header.getValue());
                    }
                }

                Map<String, String> parameters = mRequest.getRequestParameters();
                Map<String, String> clientAuthParams = mClientAuthentication
                        .getRequestParameters(mRequest.clientId);
                if (clientAuthParams != null) {
                    parameters.putAll(clientAuthParams);
                }

                String queryData = UriUtil.formUrlEncode(parameters);
                conn.setRequestProperty("Content-Length", String.valueOf(queryData.length()));
                OutputStreamWriter wr = new OutputStreamWriter(conn.getOutputStream());

                wr.write(queryData);
                wr.flush();

                if (conn.getResponseCode() >= HttpURLConnection.HTTP_OK
                        && conn.getResponseCode() < HttpURLConnection.HTTP_MULT_CHOICE) {
                    is = conn.getInputStream();
                } else {
                    is = conn.getErrorStream();
                }
                String response = Utils.readInputStream(is);
                return new JSONObject(response);
            } catch (IOException ex) {
                Logger.debugWithStack(ex, "Failed to complete exchange request");
                mException = AuthorizationException.fromTemplate(
                        GeneralErrors.NETWORK_ERROR, ex);
            } catch (JSONException ex) {
                Logger.debugWithStack(ex, "Failed to complete exchange request");
                mException = AuthorizationException.fromTemplate(
                        GeneralErrors.JSON_DESERIALIZATION_ERROR, ex);
            } finally {
                Utils.closeQuietly(is);
            }
            return null;
        }

        @Override
        protected void onPostExecute(JSONObject json) {
            if (mException != null) {
                mCallback.onTokenRequestCompleted(null, mException);
                return;
            }

            if (json.has(AuthorizationException.PARAM_ERROR)) {
                AuthorizationException ex;
                try {
                    String error = json.getString(AuthorizationException.PARAM_ERROR);
                    ex = AuthorizationException.fromOAuthTemplate(
                            TokenRequestErrors.byString(error),
                            error,
                            json.optString(AuthorizationException.PARAM_ERROR_DESCRIPTION, null),
                            UriUtil.parseUriIfAvailable(
                                    json.optString(AuthorizationException.PARAM_ERROR_URI)));
                } catch (JSONException jsonEx) {
                    ex = AuthorizationException.fromTemplate(
                            GeneralErrors.JSON_DESERIALIZATION_ERROR,
                            jsonEx);
                }
                mCallback.onTokenRequestCompleted(null, ex);
                return;
            }

            TokenResponse response;
            try {
                response = new TokenResponse.Builder(mRequest).fromResponseJson(json).build();
            } catch (JSONException jsonEx) {
                mCallback.onTokenRequestCompleted(null,
                        AuthorizationException.fromTemplate(
                                GeneralErrors.JSON_DESERIALIZATION_ERROR,
                                jsonEx));
                return;
            }

            Logger.debug("Token exchange with %s completed",
                    mRequest.configuration.tokenEndpoint);
            mCallback.onTokenRequestCompleted(response, null);
        }
    }


    private class TokenValidationRequestTask
            extends AsyncTask<Void, Void, JSONObject> {
        private TokenResponse mResponse;
        private TokenValidationResponseCallback mCallback;
        private ClientAuthentication mClientAuthentication;

        private AuthorizationException mException;

        TokenValidationRequestTask(TokenResponse request,
                                   @NonNull ClientAuthentication clientAuthentication,
                                   TokenValidationResponseCallback callback) {
            mResponse = request;
            mCallback = callback;
            mClientAuthentication = clientAuthentication;
        }

        @Override
        protected JSONObject doInBackground(Void... voids) {
            InputStream is = null;
            try {
                Uri uri = (mResponse.request.configuration.discoveryDoc.getJwksUri());
                HttpURLConnection conn =
                        mClientConfiguration.getConnectionBuilder()
                                .openConnection(uri);
                conn.setRequestMethod("GET");
                // conn.setDoOutput(true);

                is = new BufferedInputStream(conn.getInputStream());
                // readStream(in);
                String response = Utils.readInputStream(is);
                return new JSONObject(response);
            } catch (IOException ex) {
                Logger.debugWithStack(ex, "Failed to complete exchange request");
                mException = AuthorizationException.fromTemplate(
                        GeneralErrors.NETWORK_ERROR, ex);
            } catch (JSONException ex) {
                Logger.debugWithStack(ex, "Failed to complete exchange request");
                mException = AuthorizationException.fromTemplate(
                        GeneralErrors.JSON_DESERIALIZATION_ERROR, ex);
            } finally {
                Utils.closeQuietly(is);
            }
            return null;
        }

        @Override
        protected void onPostExecute(JSONObject json) {
            JSONObject requiredJson = null;

            try {
                String[] split = mResponse.idToken.split("\\.");

                String decodeTokenHeader = Utils.decodeBase64urlNoPadding(split[0]);
                String decodeTokenBody = Utils.decodeBase64urlNoPadding(split[1]);

                JSONObject jsonObjectHeader = new JSONObject(decodeTokenHeader);
                JSONObject jsonObjectBody = new JSONObject(decodeTokenBody);


                JSONArray jsonArray = json.getJSONArray("keys");

                for (int i = 0; i < jsonArray.length(); i++) {
                    if (jsonArray.getJSONObject(i).optString("alg")
                            .contains(jsonObjectHeader.getString("alg"))) {
                        requiredJson = jsonArray.getJSONObject(i);
                        break;
                    }
                }

                Logger.debug("Token validation with %s completed",
                        this.mResponse.request.configuration
                                .discoveryDoc.getValidateTokenEndpoint());

                if (mException != null
                        || requiredJson == null
                        || !json.optString("error", "").equals("")
                        || !jsonObjectHeader.getString("kid").equals(requiredJson.getString("kid"))
                        || !jsonObjectBody.getString("aud").equals(mResponse.request
                        .getRequestParameters().get("client_id"))) {
                    mCallback.onTokenValidationRequestCompleted(false, mException);
                    return;
                }

                Calendar calendar = Calendar.getInstance();
                calendar.setTimeInMillis(Long.parseLong(jsonObjectBody.getString("exp"))
                        * Long.parseLong("1000"));
                calendar.getTimeInMillis();

                if ((calendar.getTimeInMillis() > System.currentTimeMillis())
                        && jsonObjectBody.getString("nonce").equals(AuthState.sNonce)) {
                    mCallback.onTokenValidationRequestCompleted(true, null);
                } else {
                    mCallback.onTokenValidationRequestCompleted(false, mException);
                }
            } catch (UnsupportedEncodingException | JSONException e) {
                mCallback.onTokenValidationRequestCompleted(false, mException);
            }
        }
    }

    /**
     * Callback interface for token endpoint requests.
     *
     * @see AuthorizationService#performTokenRequest
     */
    public interface TokenResponseCallback {
        /**
         * Invoked when the request completes successfully or fails.
         * <p>Exactly one of {@code mResponse} or {@code ex} will be non-null. If
         * {@code mResponse} is {@code null}, a failure occurred during the request. This can
         * happen if a bad URI was provided, no connection to the server could be established, or
         * the mResponse JSON was incomplete or badly formatted.</p>
         *
         * @param response the retrieved token mResponse, if successful; {@code null} otherwise.
         * @param ex a description of the failure, if one occurred: {@code null} otherwise.
         * @see AuthorizationException.TokenRequestErrors
         */
        void onTokenRequestCompleted(@Nullable TokenResponse response,
                                     @Nullable AuthorizationException ex);
    }

    /**
     * Callback interface for token endpoint validation.
     *
     * @see AuthorizationService#performTokenValidation
     */
    public interface TokenValidationResponseCallback {
        /**
         * Invoked when the request completes successfully.
         * <p>Exactly one of {@code mResponse} or {@code ex} will be non-null. If
         * {@code mResponse} is {@code null}, a failure occurred during the request. This can
         * happen if a bad URI was provided, no connection to the server could be established, or
         * the mResponse JSON was incomplete or badly formatted.</p>
         *
         * @param isTokenValid the boolean value which represents if a token is valid or not.
         * @param ex a description of the failure, if one occurred: {@code null} otherwise.
         * @see AuthorizationException.TokenRequestErrors
         */
        void onTokenValidationRequestCompleted(boolean isTokenValid,
                                               @Nullable AuthorizationException ex);
    }

    private class RegistrationRequestTask
            extends AsyncTask<Void, Void, JSONObject> {
        private RegistrationRequest mRequest;
        private RegistrationResponseCallback mCallback;

        private AuthorizationException mException;

        RegistrationRequestTask(RegistrationRequest request,
                                RegistrationResponseCallback callback) {
            mRequest = request;
            mCallback = callback;
        }

        @Override
        protected JSONObject doInBackground(Void... voids) {
            InputStream is = null;
            String postData = mRequest.toJsonString();
            try {
                HttpURLConnection conn =
                        mClientConfiguration.getConnectionBuilder()
                                .openConnection(mRequest.configuration.registrationEndpoint);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Content-Length", String.valueOf(postData.length()));
                OutputStreamWriter wr = new OutputStreamWriter(conn.getOutputStream());
                wr.write(postData);
                wr.flush();

                is = conn.getInputStream();
                String response = Utils.readInputStream(is);
                return new JSONObject(response);
            } catch (IOException ex) {
                Logger.debugWithStack(ex, "Failed to complete registration request");
                mException = AuthorizationException.fromTemplate(
                        GeneralErrors.NETWORK_ERROR, ex);
            } catch (JSONException ex) {
                Logger.debugWithStack(ex, "Failed to complete registration request");
                mException = AuthorizationException.fromTemplate(
                        GeneralErrors.JSON_DESERIALIZATION_ERROR, ex);
            } finally {
                Utils.closeQuietly(is);
            }
            return null;
        }

        @Override
        protected void onPostExecute(JSONObject json) {
            if (mException != null) {
                mCallback.onRegistrationRequestCompleted(null, mException);
                return;
            }

            if (json.has(AuthorizationException.PARAM_ERROR)) {
                AuthorizationException ex;
                try {
                    String error = json.getString(AuthorizationException.PARAM_ERROR);
                    ex = AuthorizationException.fromOAuthTemplate(
                            RegistrationRequestErrors.byString(error),
                            error,
                            json.getString(AuthorizationException.PARAM_ERROR_DESCRIPTION),
                            UriUtil.parseUriIfAvailable(
                                    json.getString(AuthorizationException.PARAM_ERROR_URI)));
                } catch (JSONException jsonEx) {
                    ex = AuthorizationException.fromTemplate(
                            GeneralErrors.JSON_DESERIALIZATION_ERROR,
                            jsonEx);
                }
                mCallback.onRegistrationRequestCompleted(null, ex);
                return;
            }

            RegistrationResponse response;
            try {
                response = new RegistrationResponse.Builder(mRequest)
                        .fromResponseJson(json).build();
            } catch (JSONException jsonEx) {
                mCallback.onRegistrationRequestCompleted(null,
                        AuthorizationException.fromTemplate(
                                GeneralErrors.JSON_DESERIALIZATION_ERROR,
                                jsonEx));
                return;
            } catch (RegistrationResponse.MissingArgumentException ex) {
                Logger.errorWithStack(ex, "Malformed registration mResponse");
                mException = AuthorizationException.fromTemplate(
                        GeneralErrors.INVALID_REGISTRATION_RESPONSE,
                        ex);
                return;
            }
            Logger.debug("Dynamic registration with %s completed",
                    mRequest.configuration.registrationEndpoint);
            mCallback.onRegistrationRequestCompleted(response, null);
        }
    }

    /**
     * Callback interface for token endpoint requests.
     *
     * @see AuthorizationService#performTokenRequest
     */
    public interface RegistrationResponseCallback {
        /**
         * Invoked when the request completes successfully or fails.
         * <p>Exactly one of {@code mResponse} or {@code ex} will be non-null. If
         * {@code mResponse} is {@code null}, a failure occurred during the request. This can
         * happen if a bad URI was provided, no connection to the server could be established, or
         * the mResponse JSON was incomplete or badly formatted.</p>
         *
         * @param response the retrieved registration mResponse, if successful; {@code null}
         *                 otherwise.
         * @param ex a description of the failure, if one occurred: {@code null} otherwise.
         * @see AuthorizationException.RegistrationRequestErrors
         */
        void onRegistrationRequestCompleted(@Nullable RegistrationResponse response,
                                            @Nullable AuthorizationException ex);
    }
}
