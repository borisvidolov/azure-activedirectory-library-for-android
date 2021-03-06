/**
 * Copyright (c) Microsoft Corporation. All rights reserved. 
 */

package com.microsoft.adal;

import java.net.MalformedURLException;
import java.net.URL;
import java.security.NoSuchAlgorithmException;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.crypto.NoSuchPaddingException;

import com.microsoft.adal.AuthenticationConstants.AAD;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.content.LocalBroadcastManager;
import android.util.SparseArray;

/*
 */

/**
 * ADAL context to get access token, refresh token, and lookup from cache
 * 
 * @author omercan
 */
public class AuthenticationContext {

    private final static String TAG = "AuthenticationContext";

    private Context mContext;

    private String mAuthority;

    private boolean mValidateAuthority;

    private boolean mAuthorityValidated = false;

    private ITokenCacheStore mTokenCacheStore;

    private final static ReentrantReadWriteLock rwl = new ReentrantReadWriteLock();

    private final static Lock readLock = rwl.readLock();

    private final static Lock writeLock = rwl.writeLock();

    /**
     * delegate map is needed to handle activity recreate without asking
     * developer to handle context instance for config changes.
     */
    static SparseArray<AuthenticationRequestState> mDelegateMap = new SparseArray<AuthenticationRequestState>();

    /**
     * last set authorization callback
     */
    private AuthenticationCallback<AuthenticationResult> mAuthorizationCallback;

    /**
     * Instance validation related calls are serviced inside Discovery as a
     * module
     */
    private IDiscovery mDiscovery = new Discovery();

    /**
     * Web request handler interface to test behaviors
     */
    private IWebRequestHandler mWebRequest = new WebRequestHandler();

    /**
     * Connection service interface to test different behaviors
     */
    private IConnectionService mConnectionService = null;

    /**
     * CorrelationId set by user
     */
    private UUID mRequestCorrelationId = null;

    /**
     * Constructs context to use with known authority to get the token. It uses
     * default cache.
     * 
     * @param appContext It needs to have handle to the context to use the
     *            SharedPreferences as a Default cache storage. It does not need
     *            to be activity.
     * @param authority Authority url to send code and token requests
     * @param validateAuthority validate authority before sending token request
     * @throws NoSuchPaddingException DefaultTokenCacheStore uses encryption
     * @throws NoSuchAlgorithmException
     */
    public AuthenticationContext(Context appContext, String authority, boolean validateAuthority)
            throws NoSuchAlgorithmException, NoSuchPaddingException {
        mContext = appContext;
        mConnectionService = new DefaultConnectionService(mContext);
        checkInternetPermission();
        mAuthority = extractAuthority(authority);
        mValidateAuthority = validateAuthority;
        mTokenCacheStore = new DefaultTokenCacheStore(appContext);
    }

    /**
     * @param appContext
     * @param authority
     * @param validateAuthority
     * @param cache Set to null if you don't want cache.
     */
    public AuthenticationContext(Context appContext, String authority, boolean validateAuthority,
            ITokenCacheStore tokenCacheStore) {
        mContext = appContext;
        mConnectionService = new DefaultConnectionService(mContext);
        checkInternetPermission();
        mAuthority = extractAuthority(authority);
        mValidateAuthority = validateAuthority;
        mTokenCacheStore = tokenCacheStore;
    }

    /**
     * It will verify the authority and use the given cache. If cache is null,
     * it will not use cache.
     * 
     * @param appContext
     * @param authority
     * @param cache
     */
    public AuthenticationContext(Context appContext, String authority,
            ITokenCacheStore tokenCacheStore) {
        mContext = appContext;
        mConnectionService = new DefaultConnectionService(mContext);
        checkInternetPermission();
        mAuthority = extractAuthority(authority);
        mValidateAuthority = true;
        mTokenCacheStore = tokenCacheStore;
    }

    /**
     * returns referenced cache. You can use default cache, which uses
     * SharedPreferences and handles synchronization by itself.
     * 
     * @return
     */
    public ITokenCacheStore getCache() {
        return mTokenCacheStore;
    }

    /**
     * gets authority that is used for this object of AuthenticationContext
     * 
     * @return
     */
    public String getAuthority() {
        return mAuthority;
    }

    public boolean getValidateAuthority() {
        return mValidateAuthority;
    }

    /**
     * acquire Token will start interactive flow if needed. It checks the cache
     * to return existing result if not expired. It tries to use refresh token
     * if available. If it fails to get token with refresh token, it will remove
     * this refresh token from cache and start authentication.
     * 
     * @param activity required to launch authentication activity.
     * @param resource required resource identifier.
     * @param clientId required client identifier
     * @param redirectUri Optional. It will use package name info if not
     *            provided.
     * @param userId Optional.
     * @param callback required
     */
    public void acquireToken(Activity activity, String resource, String clientId,
            String redirectUri, String userId, AuthenticationCallback<AuthenticationResult> callback) {

        redirectUri = checkInputParameters(activity, resource, clientId, redirectUri, callback);

        final AuthenticationRequest request = new AuthenticationRequest(mAuthority, resource,
                clientId, redirectUri, userId, PromptBehavior.Auto, null, mRequestCorrelationId);

        acquireTokenLocal(activity, request, callback);
    }

    /**
     * acquire Token will start interactive flow if needed. It checks the cache
     * to return existing result if not expired. It tries to use refresh token
     * if available. If it fails to get token with refresh token, it will remove
     * this refresh token from cache and fall back on the UI.
     * 
     * @param activity Calling activity
     * @param resource
     * @param clientId
     * @param redirectUri Optional. It will use packagename and provided suffix
     *            for this.
     * @param userId Optional. This parameter will be used to pre-populate the
     *            username field in the authentication form. Please note that
     *            the end user can still edit the username field and
     *            authenticate as a different user. This parameter can be null.
     * @param extraQueryParameters Optional. This parameter will be appended as
     *            is to the query string in the HTTP authentication request to
     *            the authority. The parameter can be null.
     * @param callback
     */
    public void acquireToken(Activity activity, String resource, String clientId,
            String redirectUri, String userId, String extraQueryParameters,
            AuthenticationCallback<AuthenticationResult> callback) {

        redirectUri = checkInputParameters(activity, resource, clientId, redirectUri, callback);

        final AuthenticationRequest request = new AuthenticationRequest(mAuthority, resource,
                clientId, redirectUri, userId, PromptBehavior.Auto, extraQueryParameters,
                mRequestCorrelationId);

        acquireTokenLocal(activity, request, callback);

    }

    /**
     * acquire Token will start interactive flow if needed. It checks the cache
     * to return existing result if not expired. It tries to use refresh token
     * if available. If it fails to get token with refresh token, behavior will
     * depend on options. If promptbehavior is AUTO, it will remove this refresh
     * token from cache and fall back on the UI. If promptbehavior is NEVER, It
     * will remove this refresh token from cache and return error. Default is
     * AUTO. if promptbehavior is Always, it will display prompt screen.
     * 
     * @param activity
     * @param resource
     * @param clientId
     * @param redirectUri Optional. It will use packagename and provided suffix
     *            for this.
     * @param prompt Optional. added as query parameter to authorization url
     * @param callback
     */
    public void acquireToken(Activity activity, String resource, String clientId,
            String redirectUri, PromptBehavior prompt,
            AuthenticationCallback<AuthenticationResult> callback) {

        redirectUri = checkInputParameters(activity, resource, clientId, redirectUri, callback);

        final AuthenticationRequest request = new AuthenticationRequest(mAuthority, resource,
                clientId, redirectUri, null, prompt, null, mRequestCorrelationId);

        acquireTokenLocal(activity, request, callback);
    }

    /**
     * acquire Token will start interactive flow if needed. It checks the cache
     * to return existing result if not expired. It tries to use refresh token
     * if available. If it fails to get token with refresh token, behavior will
     * depend on options. If promptbehavior is AUTO, it will remove this refresh
     * token from cache and fall back on the UI if activitycontext is not null.
     * If promptbehavior is NEVER, It will remove this refresh token from cache
     * and(or not, depending on the promptBehavior values. Default is AUTO.
     * 
     * @param activity
     * @param resource
     * @param clientId
     * @param redirectUri Optional. It will use packagename and provided suffix
     *            for this.
     * @param prompt Optional. added as query parameter to authorization url
     * @param extraQueryParameters Optional. added to authorization url
     * @param callback
     */
    public void acquireToken(Activity activity, String resource, String clientId,
            String redirectUri, PromptBehavior prompt, String extraQueryParameters,
            AuthenticationCallback<AuthenticationResult> callback) {

        redirectUri = checkInputParameters(activity, resource, clientId, redirectUri, callback);

        final AuthenticationRequest request = new AuthenticationRequest(mAuthority, resource,
                clientId, redirectUri, null, prompt, extraQueryParameters, mRequestCorrelationId);

        acquireTokenLocal(activity, request, callback);
    }

    /**
     * acquire Token will start interactive flow if needed. It checks the cache
     * to return existing result if not expired. It tries to use refresh token
     * if available. If it fails to get token with refresh token, behavior will
     * depend on options. If promptbehavior is AUTO, it will remove this refresh
     * token from cache and fall back on the UI if activitycontext is not null.
     * If promptbehavior is NEVER, It will remove this refresh token from cache
     * and(or not, depending on the promptBehavior values. Default is AUTO.
     * 
     * @param activity
     * @param resource
     * @param clientId
     * @param redirectUri Optional. It will use packagename and provided suffix
     *            for this.
     * @param userId Optional. It is used for cache and as a loginhint at
     *            authentication.
     * @param prompt Optional. added as query parameter to authorization url
     * @param extraQueryParameters Optional. added to authorization url
     * @param callback
     */
    public void acquireToken(Activity activity, String resource, String clientId,
            String redirectUri, String userId, PromptBehavior prompt, String extraQueryParameters,
            AuthenticationCallback<AuthenticationResult> callback) {

        redirectUri = checkInputParameters(activity, resource, clientId, redirectUri, callback);

        final AuthenticationRequest request = new AuthenticationRequest(mAuthority, resource,
                clientId, redirectUri, userId, prompt, extraQueryParameters, mRequestCorrelationId);

        acquireTokenLocal(activity, request, callback);
    }

    private String checkInputParameters(Activity activity, String resource, String clientId,
            String redirectUri, AuthenticationCallback<AuthenticationResult> callback) {
        if (mContext == null) {
            throw new AuthenticationException(ADALError.DEVELOPER_CONTEXT_IS_NOT_PROVIDED);
        }

        if (activity == null) {
            throw new IllegalArgumentException("activity");
        }

        if (StringExtensions.IsNullOrBlank(resource)) {
            throw new IllegalArgumentException("resource");
        }

        if (StringExtensions.IsNullOrBlank(clientId)) {
            throw new IllegalArgumentException("clientId");
        }

        if (callback == null) {
            throw new IllegalArgumentException("callback");
        }

        if (StringExtensions.IsNullOrBlank(redirectUri)) {
            redirectUri = getRedirectFromPackage();
        }

        return redirectUri;
    }

    /**
     * acquire token using refresh token if cache is not used. Otherwise, use
     * acquireToken to let the ADAL handle the cache lookup and refresh token
     * request.
     * 
     * @param refreshToken Required.
     * @param clientId Required.
     * @param callback Required
     */
    public void acquireTokenByRefreshToken(String refreshToken, String clientId,
            AuthenticationCallback<AuthenticationResult> callback) {
        refreshTokenWithoutCache(refreshToken, clientId, null, callback);
    }

    /**
     * acquire token using refresh token if cache is not used. Otherwise, use
     * acquireToken to let the ADAL handle the cache lookup and refresh token
     * request.
     * 
     * @param refreshToken Required.
     * @param clientId Required.
     * @param resource
     * @param callback Required
     */
    public void acquireTokenByRefreshToken(String refreshToken, String clientId, String resource,
            AuthenticationCallback<AuthenticationResult> callback) {
        refreshTokenWithoutCache(refreshToken, clientId, resource, callback);
    }

    /**
     * Call from your onActivityResult method inside your activity that started
     * token request. This is needed to process the call when credential screen
     * completes. This method wraps the implementation for onActivityResult at
     * the related Activity class.
     * 
     * @param requestCode
     * @param resultCode
     * @param data
     */
    public void onActivityResult(int requestCode, int resultCode, Intent data) {

        // This is called at UI thread.
        if (requestCode == AuthenticationConstants.UIRequest.BROWSER_FLOW) {
            if (data == null) {
                // If data is null, RequestId is unknown. It could not find
                // callback to respond to this request.
                Logger.e(TAG, "onActivityResult BROWSER_FLOW data is null", null,
                        ADALError.ON_ACTIVITY_RESULT_INTENT_NULL);
            } else {
                Bundle extras = data.getExtras();
                final int requestId = extras.getInt(AuthenticationConstants.Browser.REQUEST_ID);
                Logger.v(TAG, "onActivityResult RequestId:" + requestId);
                final AuthenticationRequestState waitingRequest = getWaitingRequest(requestId);

                if (resultCode == AuthenticationConstants.UIResponse.BROWSER_CODE_CANCEL) {
                    // User cancelled the flow
                    Logger.v(TAG, "User cancelled the flow" + requestId);
                    waitingRequestOnError(waitingRequest, requestId,
                            new AuthenticationCancelError());

                } else if (resultCode == AuthenticationConstants.UIResponse.BROWSER_CODE_ERROR) {
                    String errCode = extras
                            .getString(AuthenticationConstants.Browser.RESPONSE_ERROR_CODE);
                    String errMessage = extras
                            .getString(AuthenticationConstants.Browser.RESPONSE_ERROR_MESSAGE);
                    Logger.v(TAG, "Error info:" + errCode + " " + errMessage + " for requestId: "
                            + requestId);
                    waitingRequestOnError(waitingRequest, requestId, new AuthenticationException(
                            ADALError.SERVER_INVALID_REQUEST, errCode + " " + errMessage));

                } else if (resultCode == AuthenticationConstants.UIResponse.BROWSER_CODE_COMPLETE) {
                    // Browser has the url and finished the processing to get
                    // token
                    final AuthenticationRequest authenticationRequest = (AuthenticationRequest)extras
                            .getSerializable(AuthenticationConstants.Browser.RESPONSE_REQUEST_INFO);

                    String endingUrl = extras
                            .getString(AuthenticationConstants.Browser.RESPONSE_FINAL_URL);

                    if (endingUrl.isEmpty()) {
                        Logger.v(TAG, "Webview did not reach the redirectUrl");
                        waitingRequestOnError(waitingRequest, requestId,
                                new IllegalArgumentException(
                                        "Webview did not reach the redirectUrl"));

                    } else {
                        Oauth2 oauthRequest = new Oauth2(authenticationRequest, mWebRequest);
                        Logger.v(TAG,
                                "Processing url for token. " + authenticationRequest.getLogInfo());

                        oauthRequest.getToken(endingUrl,
                                new AuthenticationCallback<AuthenticationResult>() {

                                    @Override
                                    public void onSuccess(AuthenticationResult result) {
                                        Logger.v(TAG, "OnActivityResult processed the result. "
                                                + authenticationRequest.getLogInfo());
                                        if (result != null
                                                && !StringExtensions.IsNullOrBlank(result
                                                        .getAccessToken())) {
                                            Logger.v(TAG,
                                                    "OnActivityResult is setting the token to cache. "
                                                            + authenticationRequest.getLogInfo());
                                            setItemToCache(authenticationRequest, result);
                                        }

                                        if (waitingRequest != null
                                                && waitingRequest.mDelagete != null) {
                                            Logger.v(TAG, "Sending result to callback. "
                                                    + authenticationRequest.getLogInfo());
                                            waitingRequest.mDelagete.onSuccess(result);
                                        }
                                        removeWaitingRequest(requestId);
                                    }

                                    @Override
                                    public void onError(Exception exc) {
                                        Logger.e(
                                                TAG,
                                                "Error in processing code to get token. "
                                                        + authenticationRequest.getLogInfo(),
                                                ExceptionExtensions.getExceptionMessage(exc),
                                                ADALError.AUTHORIZATION_CODE_NOT_EXCHANGED_FOR_TOKEN,
                                                exc);
                                        waitingRequestOnError(waitingRequest, requestId, exc);
                                    }
                                });
                    }
                }
            }
        }
    }

    private void waitingRequestOnError(final AuthenticationRequestState waitingRequest,
            int requestId, Exception exc) {

        if (waitingRequest != null && waitingRequest.mDelagete != null) {
            Logger.v(TAG, "Sending error to callback");
            waitingRequest.mDelagete.onError(exc);
        }
        removeWaitingRequest(requestId);
    }

    private void removeWaitingRequest(int requestId) {
        Logger.v(TAG, "Remove waiting request: " + requestId);

        writeLock.lock();
        try {
            mDelegateMap.remove(requestId);
        } finally {
            writeLock.unlock();
        }
    }

    private AuthenticationRequestState getWaitingRequest(int requestId) {
        Logger.v(TAG, "Get waiting request: " + requestId);
        AuthenticationRequestState request = null;

        readLock.lock();
        try {
            request = mDelegateMap.get(requestId);
        } finally {
            readLock.unlock();
        }

        if (request == null && mAuthorizationCallback != null
                && requestId == mAuthorizationCallback.hashCode()) {
            // it does not have the caller callback. It will check the last
            // callback if set
            Logger.e(TAG, "Request callback is not available for requestid:" + requestId
                    + ". It will use last callback.", "", ADALError.CALLBACK_IS_NOT_FOUND);
            request = new AuthenticationRequestState(0, null, mAuthorizationCallback);
        }

        return request;
    }

    private void putWaitingRequest(int requestId, AuthenticationRequestState requestState) {
        Logger.v(TAG, "Put waiting request: " + requestId);
        if (requestState != null) {
            writeLock.lock();

            try {
                mDelegateMap.put(requestId, requestState);
            } finally {
                writeLock.unlock();
            }
        }
    }

    /**
     * Active authentication activity can be cancelled if it exists. It may not
     * be cancelled if activity is not launched yet. RequestId is the hashcode
     * of your AuthenticationCallback.
     * 
     * @return true: if there is a valid waiting request and cancel message send
     *         successfully. false: Request does not exist or cancel message not
     *         send
     */
    public boolean cancelAuthenticationActivity(int requestId) {

        AuthenticationRequestState request = getWaitingRequest(requestId);

        if (request == null || request.mDelagete == null) {
            // there is not any waiting callback
            Logger.v(TAG, "Current callback is empty. There is not any active authentication.");
            return true;
        }

        Logger.v(TAG, "Current callback is not empty. There is an active authentication Activity.");

        // intent to cancel. Authentication activity registers for this message
        // at onCreate event.
        final Intent intent = new Intent(AuthenticationConstants.Browser.ACTION_CANCEL);
        final Bundle extras = new Bundle();
        intent.putExtras(extras);
        intent.putExtra(AuthenticationConstants.Browser.REQUEST_ID, requestId);
        // send intent to cancel any active authentication activity.
        // it may not cancel it, if activity takes some time to launch.

        boolean cancelResult = LocalBroadcastManager.getInstance(mContext).sendBroadcast(intent);
        if (cancelResult) {
            // clear callback if broadcast message was successful
            Logger.v(TAG, "Cancel broadcast message was successful.");
            request.mCancelled = true;
            request.mDelagete.onError(new AuthenticationCancelError());
        } else {
            // Activity is not launched yet or receiver is not registered
            Logger.w(TAG, "Cancel broadcast message was not successful.", "",
                    ADALError.BROADCAST_CANCEL_NOT_SUCCESSFUL);
        }

        return cancelResult;
    }

    interface ResponseCallback {
        public void onRequestComplete(HashMap<String, String> response);
    }

    /**
     * only gets token from activity defined in this package
     * 
     * @param activity
     * @param request
     * @param prompt
     * @param callback
     */
    private void acquireTokenLocal(final Activity activity, final AuthenticationRequest request,
            final AuthenticationCallback<AuthenticationResult> externalCall) {

        final URL authorityUrl;
        try {
            authorityUrl = new URL(mAuthority);
        } catch (MalformedURLException e) {
            externalCall.onError(new AuthenticationException(
                    ADALError.DEVELOPER_AUTHORITY_IS_NOT_VALID_URL));
            return;
        }

        if (mValidateAuthority && !mAuthorityValidated) {
            validateAuthority(authorityUrl, new AuthenticationCallback<Boolean>() {

                @Override
                public void onSuccess(Boolean result) {
                    if (result) {
                        mAuthorityValidated = true;
                        Logger.v(TAG, "Authority is validated: " + authorityUrl.toString());
                        acquireTokenAfterValidation(activity, request, externalCall);
                    } else {
                        Logger.v(TAG, "Call external callback since instance is invalid"
                                + authorityUrl.toString());
                        externalCall.onError(new AuthenticationException(
                                ADALError.DEVELOPER_AUTHORITY_IS_NOT_VALID_INSTANCE));
                    }
                }

                @Override
                public void onError(Exception exc) {
                    Logger.e(TAG, "Authority validation has an error", "",
                            ADALError.DEVELOPER_AUTHORITY_IS_NOT_VALID_INSTANCE, exc);
                    externalCall.onError(new AuthenticationException(
                            ADALError.DEVELOPER_AUTHORITY_IS_NOT_VALID_INSTANCE));
                }

            });
        } else {
            acquireTokenAfterValidation(activity, request, externalCall);
        }
    }

    private void acquireTokenAfterValidation(final Activity activity,
            final AuthenticationRequest request,
            final AuthenticationCallback<AuthenticationResult> externalCall) {
        Logger.v(TAG, "Token request started");

        // Lookup access token from cache
        AuthenticationResult cachedItem = getItemFromCache(request);
        if (request.getPrompt() != PromptBehavior.Always && isValidCache(cachedItem)) {
            Logger.v(TAG, "Token is returned from cache");
            externalCall.onSuccess(cachedItem);
            return;
        }

        Logger.v(TAG, "Checking refresh tokens");
        RefreshItem refreshItem = getRefreshToken(request);

        if (request.getPrompt() != PromptBehavior.Always && refreshItem != null
                && !StringExtensions.IsNullOrBlank(refreshItem.mRefreshToken)) {
            Logger.v(TAG, "Refresh token is available and it will attempt to refresh token");
            refreshToken(activity, request, refreshItem, true, externalCall);
        } else {
            Logger.v(TAG, "Refresh token is not available");
            if (request.getPrompt() != PromptBehavior.Never) {
                // start activity if other options are not available
                // delegate map is used to remember callback if another
                // instance of authenticationContext is created for config
                // change or similar at client app.
                mAuthorizationCallback = externalCall;
                request.setRequestId(externalCall.hashCode());
                Logger.v(TAG,
                        "Starting Authentication Activity with callback:" + externalCall.hashCode());
                putWaitingRequest(externalCall.hashCode(), new AuthenticationRequestState(
                        externalCall.hashCode(), request, externalCall));

                if (!startAuthenticationActivity(activity, request)) {
                    mAuthorizationCallback.onError(new AuthenticationException(
                            ADALError.DEVELOPER_ACTIVITY_IS_NOT_RESOLVED));
                }
            } else {
                // it can come here if user set to never for the prompt and
                // refresh token failed.
                Logger.e(TAG,
                        "Prompt is not allowed and failed to get token:" + externalCall.hashCode(),
                        "", ADALError.AUTH_REFRESH_FAILED_PROMPT_NOT_ALLOWED);
                externalCall.onError(new AuthenticationException(
                        ADALError.AUTH_REFRESH_FAILED_PROMPT_NOT_ALLOWED));

            }
        }
    }

    protected boolean isRefreshable(AuthenticationResult cachedItem) {
        return cachedItem != null && !StringExtensions.IsNullOrBlank(cachedItem.getRefreshToken());
    }

    private boolean isValidCache(AuthenticationResult cachedItem) {
        if (cachedItem != null && !StringExtensions.IsNullOrBlank(cachedItem.getAccessToken())
                && !isExpired(cachedItem.getExpiresOn())) {
            return true;
        }
        return false;
    }

    private boolean isExpired(Date expires) {
        Date validity = getCurrentTime().getTime();

        if (expires != null && expires.before(validity))
            return true;

        return false;
    }

    private static Calendar getCurrentTime() {
        Calendar timeAhead = Calendar.getInstance();
        return timeAhead;
    }

    /**
     * get token from cache to return it, if not expired
     * 
     * @param request
     * @return
     */
    private AuthenticationResult getItemFromCache(final AuthenticationRequest request) {
        if (mTokenCacheStore != null) {
            // get token if resourceid matches to cache key.
            TokenCacheItem item = mTokenCacheStore.getItem(CacheKey.createCacheKey(request));
            if (item != null) {
                AuthenticationResult result = new AuthenticationResult(item.getAccessToken(),
                        item.getRefreshToken(), item.getExpiresOn(),
                        item.getIsMultiResourceRefreshToken());
                return result;
            }
        }
        return null;
    }

    /**
     * If refresh token fails, this needs to be removed from cache to not use
     * this again for next try. Error in refreshToken call will result in
     * another call to acquireToken. It may try multi resource refresh token for
     * second attempt.
     */
    private class RefreshItem {
        String mRefreshToken;

        String mKey;

        public RefreshItem(String keyInCache, String refreshTokenValue) {
            this.mKey = keyInCache;
            this.mRefreshToken = refreshTokenValue;
        }
    }

    private RefreshItem getRefreshToken(final AuthenticationRequest request) {
        RefreshItem refreshItem = null;

        if (mTokenCacheStore != null) {
            // target refreshToken for this resource first. CacheKey will
            // include the resourceId in the cachekey
            Logger.v(TAG, "Looking for regular refresh token");
            String keyUsed = CacheKey.createCacheKey(request);
            TokenCacheItem item = mTokenCacheStore.getItem(keyUsed);

            if (item == null || StringExtensions.IsNullOrBlank(item.getRefreshToken())) {
                // if not present, check multiResource item in cache. Cache key
                // will not include resourceId in the cache key string.
                Logger.v(TAG, "Looking for Multi Resource Refresh token");
                keyUsed = CacheKey.createMultiResourceRefreshTokenKey(request);
                item = mTokenCacheStore.getItem(keyUsed);
            }

            if (item != null && !StringExtensions.IsNullOrBlank(item.getRefreshToken())) {
                Logger.v(TAG, "Refresh token is available. Key used:" + keyUsed);
                refreshItem = new RefreshItem(keyUsed, item.getRefreshToken());
            }
        }

        return refreshItem;
    }

    private void setItemToCache(final AuthenticationRequest request, AuthenticationResult result)
            throws AuthenticationException {
        if (mTokenCacheStore != null) {
            // Store token
            Logger.v(TAG, "Setting item to cache");
            mTokenCacheStore.setItem(CacheKey.createCacheKey(request), new TokenCacheItem(request,
                    result, false));

            // Store broad refresh token if available
            if (result.getIsMultiResourceRefreshToken()) {
                Logger.v(TAG, "Setting Multi Resource Refresh token to cache");
                mTokenCacheStore.setItem(CacheKey.createMultiResourceRefreshTokenKey(request),
                        new TokenCacheItem(request, result, true));
            }
        }
    }

    private void removeItemFromCache(final RefreshItem refreshItem) throws AuthenticationException {
        if (mTokenCacheStore != null) {
            Logger.v(TAG, "Remove refresh item from cache:" + refreshItem.mKey);
            mTokenCacheStore.removeItem(refreshItem.mKey);
        }
    }

    /**
     * refresh token if possible. if it fails, it calls acquire token after
     * removing refresh token from cache.
     * 
     * @param activity Activity to use in case refresh token does not succeed
     *            and prompt is not set to never.
     * @param request incoming request
     * @param refreshItem refresh item info to remove this refresh token from
     *            cache
     * @param useCache refresh request can be explicit without cache usage.
     *            Error message should return without trying prompt.
     * @param externalCallback
     */
    private void refreshToken(final Activity activity, final AuthenticationRequest request,
            final RefreshItem refreshItem, final boolean useCache,
            final AuthenticationCallback<AuthenticationResult> externalCallback) {

        Logger.v(TAG, "Process refreshToken for " + request.getLogInfo());

        // Removes refresh token from cache, when this call is complete. Request
        // may be interrupted, if app is shutdown by user. Detect connection
        // state to not remove refresh token if user turned Airplane mode or
        // similar.
        if (!mConnectionService.isConnectionAvailable()) {
            Logger.w(TAG, "Connection is not available to refresh token", request.getLogInfo(),
                    ADALError.DEVICE_CONNECTION_IS_NOT_AVAILABLE);
            externalCallback.onError(new AuthenticationException(
                    ADALError.DEVICE_CONNECTION_IS_NOT_AVAILABLE));
        }

        Oauth2 oauthRequest = new Oauth2(request, mWebRequest);
        oauthRequest.refreshToken(refreshItem.mRefreshToken,
                new AuthenticationCallback<AuthenticationResult>() {

                    @Override
                    public void onSuccess(AuthenticationResult result) {

                        if (useCache) {
                            if (result == null
                                    || StringExtensions.IsNullOrBlank(result.getAccessToken())) {
                                Logger.w(TAG, "Refresh token did not return accesstoken.",
                                        request.getLogInfo() + result.getErrorLogInfo(),
                                        ADALError.AUTH_FAILED_NO_TOKEN);

                                // remove item from cache to avoid same usage of
                                // refresh token in next acquireToken call
                                removeItemFromCache(refreshItem);
                                acquireTokenLocal(activity, request, externalCallback);
                            } else {
                                Logger.v(
                                        TAG,
                                        "Refresh token is finished. Request:"
                                                + request.getLogInfo());

                                // it replaces multi resource refresh token as
                                // well with the new one since it is not stored
                                // with resource.
                                Logger.v(
                                        TAG,
                                        "Cache is used. It will set item to cache"
                                                + request.getLogInfo());
                                setItemToCache(request, result);

                                // return result obj which has error code and
                                // error description that is returned from
                                // server response
                                externalCallback.onSuccess(result);
                            }
                        } else {
                            // User is not using cache and explicitly
                            // calling with refresh token. User should received
                            // error code and error description in
                            // Authentication result for Oauth errors
                            externalCallback.onSuccess(result);
                        }
                    }

                    @Override
                    public void onError(Exception exc) {
                        // remove item from cache
                        Logger.e(TAG, "Error in refresh token for request:" + request.getLogInfo(),
                                ExceptionExtensions.getExceptionMessage(exc),
                                ADALError.AUTH_FAILED_NO_TOKEN, exc);
                        if (useCache) {
                            Logger.v(TAG,
                                    "Error in refresh token. Cache is used. It will remove item from cache"
                                            + request.getLogInfo());
                            removeItemFromCache(refreshItem);
                        }

                        externalCallback.onError(exc);
                    }
                });
    }

    private void validateAuthority(final URL authorityUrl,
            final AuthenticationCallback<Boolean> authenticationCallback) {

        if (mDiscovery != null) {
            Logger.v(TAG, "Start validating authority");
            mDiscovery.isValidAuthority(authorityUrl, new AuthenticationCallback<Boolean>() {

                @Override
                public void onSuccess(Boolean result) {
                    Logger.v(TAG, "Instance validation is successfull. Result:" + result.toString());
                    authenticationCallback.onSuccess(result);
                }

                @Override
                public void onError(Exception exc) {
                    Logger.e(TAG, "Instance validation returned error", "",
                            ADALError.DEVELOPER_AUTHORITY_CAN_NOT_BE_VALIDED, exc);
                    authenticationCallback.onError(exc);
                }
            });
        }
    }

    private String getRedirectFromPackage() {
        return mContext.getApplicationContext().getPackageName();
    }

    /**
     * @param activity
     * @param request
     * @return false: if intent is not resolved or error in starting. true: if
     *         intent is sent to start the activity.
     */
    private boolean startAuthenticationActivity(final Activity activity,
            AuthenticationRequest request) {
        Intent intent = getAuthenticationActivityIntent(activity, request);

        if (!resolveIntent(intent)) {
            Logger.e(TAG, "Intent is not resolved", "",
                    ADALError.DEVELOPER_ACTIVITY_IS_NOT_RESOLVED);
            return false;
        }

        try {
            // Start activity from callers context so that caller can intercept
            // when it is done
            activity.startActivityForResult(intent, AuthenticationConstants.UIRequest.BROWSER_FLOW);
        } catch (ActivityNotFoundException e) {
            Logger.e(TAG, "Activity login is not found after resolving intent", "",
                    ADALError.DEVELOPER_ACTIVITY_IS_NOT_RESOLVED, e);
            return false;
        }

        return true;
    }

    /**
     * Resolve activity from the package. If developer did not declare the
     * activity, it will not resolve.
     * 
     * @param intent
     * @return true if activity is defined in the package.
     */
    final private boolean resolveIntent(Intent intent) {

        ResolveInfo resolveInfo = mContext.getPackageManager().resolveActivity(intent, 0);
        if (resolveInfo == null) {
            return false;
        }

        return true;
    }

    /**
     * get intent to start authentication activity
     * 
     * @param request
     * @return intent for authentication activity
     */
    final private Intent getAuthenticationActivityIntent(Activity activity,
            AuthenticationRequest request) {
        Intent intent = new Intent();
        intent.setClass(activity, AuthenticationActivity.class);
        intent.putExtra(AuthenticationConstants.Browser.REQUEST_MESSAGE, request);
        return intent;
    }

    /**
     * get the CorrelationId set by user
     * 
     * @return
     */
    public UUID getRequestCorrelationId() {
        return mRequestCorrelationId;
    }

    /**
     * set CorrelationId to requests
     * 
     * @param mRequestCorrelationId
     */
    public void setRequestCorrelationId(UUID mRequestCorrelationId) {
        this.mRequestCorrelationId = mRequestCorrelationId;
    }
    
    /**
     * Developer is using refresh token call to do refresh without cache usage.
     * App context or activity is not needed. Async requests are created,so this
     * needs to be called at UI thread.
     */
    private void refreshTokenWithoutCache(final String refreshToken, String clientId,
            String resource, final AuthenticationCallback<AuthenticationResult> callback) {
        Logger.v(TAG, "Refresh token without cache");

        if (StringExtensions.IsNullOrBlank(refreshToken)) {
            throw new IllegalArgumentException("Refresh token is not provided");
        }

        if (StringExtensions.IsNullOrBlank(clientId)) {
            throw new IllegalArgumentException("ClientId is not provided");
        }

        if (callback == null) {
            throw new IllegalArgumentException("Callback is not provided");
        }

        final URL authorityUrl;
        try {
            authorityUrl = new URL(mAuthority);
        } catch (MalformedURLException e) {
            Logger.e(TAG, "Authority is invalid:" + mAuthority, null,
                    ADALError.DEVELOPER_AUTHORITY_IS_NOT_VALID_URL);
            callback.onError(new AuthenticationException(
                    ADALError.DEVELOPER_AUTHORITY_IS_NOT_VALID_URL));
            return;
        }

        final AuthenticationRequest request = new AuthenticationRequest(mAuthority, resource,
                clientId, mRequestCorrelationId);
        // It is not using cache and refresh is not expected to show
        // authentication activity.
        request.setPrompt(PromptBehavior.Never);
        final RefreshItem refreshItem = new RefreshItem("", refreshToken);

        if (mValidateAuthority) {
            Logger.v(TAG, "Validating authority");
            validateAuthority(authorityUrl, new AuthenticationCallback<Boolean>() {

                // These methods are called at UI thread. Async Task
                // calls the
                // callback at onPostExecute which happens at UI thread.
                @Override
                public void onSuccess(Boolean result) {
                    if (result) {
                        // it does one attempt since it is not using
                        // cache
                        Logger.v(TAG, "Authority is validated" + authorityUrl.toString());
                        refreshToken(null, request, refreshItem, false, callback);
                    } else {
                        Logger.v(
                                TAG,
                                "Call callback since instance is invalid:"
                                        + authorityUrl.toString());
                        callback.onError(new AuthenticationException(
                                ADALError.DEVELOPER_AUTHORITY_IS_NOT_VALID_INSTANCE));
                    }
                }

                @Override
                public void onError(Exception exc) {
                    Logger.e(TAG, "Authority validation is failed",
                            ExceptionExtensions.getExceptionMessage(exc),
                            ADALError.SERVER_INVALID_REQUEST, exc);
                    callback.onError(exc);
                }
            });
        } else {
            Logger.v(TAG, "Skip authority validation");
            refreshToken(null, request, refreshItem, false, callback);
        }
    }

    private static String extractAuthority(String authority) {
        if (!StringExtensions.IsNullOrBlank(authority)) {

            // excluding the starting https:// or http://
            int thirdSlash = authority.indexOf("/", 8);

            // third slash is not the last character
            if (thirdSlash >= 0 && thirdSlash != (authority.length() - 1)) {
                int fourthSlash = authority.indexOf("/", thirdSlash + 1);
                if (fourthSlash < 0 || fourthSlash > thirdSlash + 1) {
                    if (fourthSlash >= 0) {
                        return authority.substring(0, fourthSlash);
                    }

                    return authority;
                }
            }
        }

        throw new IllegalArgumentException("authority");
    }

    private void checkInternetPermission() {
        PackageManager pm = mContext.getPackageManager();
        if (PackageManager.PERMISSION_GRANTED != pm.checkPermission("android.permission.INTERNET",
                mContext.getPackageName())) {
            throw new AuthenticationException(ADALError.DEVELOPER_INTERNET_PERMISSION_MISSING);
        }
    }

    class DefaultConnectionService implements IConnectionService {

        private Context mConnectionContext;

        DefaultConnectionService(Context ctx) {
            mConnectionContext = ctx;
        }

        public boolean isConnectionAvailable() {
            ConnectivityManager connectivityManager = (ConnectivityManager)mConnectionContext
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo activeNetwork = connectivityManager.getActiveNetworkInfo();
            boolean isConnected = activeNetwork != null && activeNetwork.isConnectedOrConnecting();
            return isConnected;
        }
    }

    /**
     * Version name for ADAL not for the app itself
     * 
     * @return
     */
    public static String getVersionName() {
        // Package manager does not report for ADAL
        // AndroidManifest files are not merged, so it is returning hard coded value
        return "0.5";
    }
}
