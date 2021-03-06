
package com.microsoft.adal.test;

import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.NoSuchAlgorithmException;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import junit.framework.Assert;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.test.AndroidTestCase;
import android.test.UiThreadTest;
import android.test.mock.MockContext;
import android.test.mock.MockPackageManager;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;
import android.util.SparseArray;

import com.microsoft.adal.ADALError;
import com.microsoft.adal.AuthenticationActivity;
import com.microsoft.adal.AuthenticationCallback;
import com.microsoft.adal.AuthenticationContext;
import com.microsoft.adal.AuthenticationException;
import com.microsoft.adal.AuthenticationSettings;
import com.microsoft.adal.CacheKey;
import com.microsoft.adal.DefaultTokenCacheStore;
import com.microsoft.adal.HttpWebResponse;
import com.microsoft.adal.IConnectionService;
import com.microsoft.adal.IDiscovery;
import com.microsoft.adal.ITokenCacheStore;
import com.microsoft.adal.Logger;
import com.microsoft.adal.Logger.ILogger;
import com.microsoft.adal.Logger.LogLevel;
import com.microsoft.adal.PromptBehavior;
import com.microsoft.adal.TokenCacheItem;
import com.microsoft.adal.test.AuthenticationConstants.AAD;
import com.microsoft.adal.test.AuthenticationConstants.UIRequest;

public class AuthenticationContextTests extends AndroidTestCase {

    /**
     * check case-insensitive lookup
     */
    private static final String VALID_AUTHORITY = "https://Login.windows.net/Omercantest.Onmicrosoft.com";

    protected final static int CONTEXT_REQUEST_TIME_OUT = 20000;

    private final static String TEST_AUTHORITY = "http://login.windows.net/common";

    private static final String TAG = "AuthenticationContextTests";

    protected void setUp() throws Exception {
        super.setUp();
        Log.d(TAG, "setup key at settings");
        if (AuthenticationSettings.INSTANCE.getSecretKeyData() == null) {
            // use same key for tests
            SecretKeyFactory keyFactory = SecretKeyFactory
                    .getInstance("PBEWithSHA256And256BitAES-CBC-BC");
            SecretKey tempkey = keyFactory.generateSecret(new PBEKeySpec("test".toCharArray(),
                    "abcdedfdfd".getBytes("UTF-8"), 100, 256));
            SecretKey secretKey = new SecretKeySpec(tempkey.getEncoded(), "AES");
            AuthenticationSettings.INSTANCE.setSecretKey(secretKey.getEncoded());
        }
    }

    protected void tearDown() throws Exception {
        Logger.getInstance().setExternalLogger(null);
        super.tearDown();
    }

    /**
     * test constructor to make sure authority parameter is set
     * 
     * @throws NoSuchPaddingException
     * @throws NoSuchAlgorithmException
     */
    public void testConstructor() throws NoSuchAlgorithmException, NoSuchPaddingException {
        testAuthorityTrim("authorityFail");
        testAuthorityTrim("https://msft.com////");
        testAuthorityTrim("https:////");
        AuthenticationContext context2 = new AuthenticationContext(getContext(),
                "https://github.com/MSOpenTech/some/some", false);
        assertEquals("https://github.com/MSOpenTech", context2.getAuthority());
    }

    private void testAuthorityTrim(String authority) throws NoSuchAlgorithmException,
            NoSuchPaddingException {
        try {
            new AuthenticationContext(getContext(), authority, false);
            Assert.fail("expected to fail");
        } catch (IllegalArgumentException e) {
            assertTrue("authority in the msg", e.getMessage().contains("authority"));
        }
    }

    public void testConstructorNoCache() {
        String authority = "https://github.com/MSOpenTech";
        AuthenticationContext context = new AuthenticationContext(getContext(), authority, false,
                null);
        assertNull(context.getCache());
    }

    public void testConstructorWithCache() throws NoSuchAlgorithmException, NoSuchPaddingException {
        String authority = "https://github.com/MSOpenTech";
        DefaultTokenCacheStore expected = new DefaultTokenCacheStore(getContext());
        AuthenticationContext context = new AuthenticationContext(getContext(), authority, false,
                expected);
        assertEquals("Cache object is expected to be same", expected, context.getCache());

        AuthenticationContext contextDefaultCache = new AuthenticationContext(getContext(),
                authority, false);
        assertNotNull(contextDefaultCache.getCache());
    }

    public void testConstructor_InternetPermission() throws NoSuchAlgorithmException,
            NoSuchPaddingException {
        String authority = "https://github.com/MSOpenTech";
        FileMockContext mockContext = new FileMockContext(getContext());
        mockContext.requestedPermissionName = "android.permission.INTERNET";
        mockContext.responsePermissionFlag = PackageManager.PERMISSION_GRANTED;

        // no exception
        new AuthenticationContext(mockContext, authority, false);

        try {
            mockContext.responsePermissionFlag = PackageManager.PERMISSION_DENIED;
            new AuthenticationContext(mockContext, authority, false);
            Assert.fail("Supposed to fail");
        } catch (Exception e) {

            assertEquals("Permission related message",
                    ADALError.DEVELOPER_INTERNET_PERMISSION_MISSING,
                    ((AuthenticationException)e).getCode());
        }
    }

    public void testConstructorValidateAuthority() throws NoSuchAlgorithmException,
            NoSuchPaddingException {

        String authority = "https://github.com/MSOpenTech";
        AuthenticationContext context = new AuthenticationContext(getContext(), authority, true);
        assertTrue("Validate flag is expected to be same", context.getValidateAuthority());

        context = new AuthenticationContext(getContext(), authority, false);
        assertFalse("Validate flag is expected to be same", context.getValidateAuthority());
    }

    public void testCorrelationId_setAndGet() throws NoSuchAlgorithmException,
            NoSuchPaddingException {
        UUID requestCorrelationId = UUID.randomUUID();
        AuthenticationContext context = new AuthenticationContext(getContext(), TEST_AUTHORITY,
                true);
        context.setRequestCorrelationId(requestCorrelationId);
        assertEquals("Verifier getter and setter", requestCorrelationId,
                context.getRequestCorrelationId());
    }

    /**
     * External call to Service to get real error response. Add expired item in
     * cache to try refresh token request. Web Request should have correlationId
     * in the header.
     * 
     * @throws NoSuchFieldException
     * @throws IllegalAccessException
     * @throws InterruptedException
     * @throws NoSuchPaddingException
     * @throws NoSuchAlgorithmException
     */
    @MediumTest
    @UiThreadTest
    public void testCorrelationId_InWebRequest() throws NoSuchFieldException,
            IllegalAccessException, InterruptedException, NoSuchAlgorithmException,
            NoSuchPaddingException {
        FileMockContext mockContext = new FileMockContext(getContext());
        String expectedAccessToken = "TokenFortestAcquireToken" + UUID.randomUUID().toString();
        String expectedClientId = "client" + UUID.randomUUID().toString();
        String expectedResource = "resource" + UUID.randomUUID().toString();
        String expectedUser = "userid" + UUID.randomUUID().toString();
        ITokenCacheStore mockCache = getMockCache(-30, expectedAccessToken, expectedResource,
                expectedClientId, expectedUser, false);
        final AuthenticationContext context = new AuthenticationContext(mockContext,
                VALID_AUTHORITY, false, mockCache);
        setConnectionAvailable(context, true);
        UUID requestCorrelationId = UUID.randomUUID();

        final CountDownLatch signal = new CountDownLatch(1);
        MockAuthenticationCallback callback = new MockAuthenticationCallback(signal);
        final TestLogResponse response = new TestLogResponse();
        listenForLogMessage(response, "Refresh token did not return accesstoken.");

        // Call acquire token with prompt never to not attempt to launch
        // activity
        context.setRequestCorrelationId(requestCorrelationId);
        context.acquireToken(new MockActivity(), expectedResource, expectedClientId, "redirect",
                expectedUser, PromptBehavior.Never, null, callback);
        signal.await(CONTEXT_REQUEST_TIME_OUT, TimeUnit.MILLISECONDS);

        // Verify that web request send correct headers
        assertTrue("Server response has same correlationId",
                response.additionalMessage.contains(requestCorrelationId.toString()));
    }

    /**
     * if package does not have declaration for activity, it should return false
     * 
     * @throws InvocationTargetException
     * @throws IllegalAccessException
     * @throws IllegalArgumentException
     * @throws InstantiationException
     * @throws NoSuchMethodException
     * @throws ClassNotFoundException
     * @throws NoSuchPaddingException
     * @throws NoSuchAlgorithmException
     */
    @SmallTest
    public void testResolveIntent() throws IllegalArgumentException, IllegalAccessException,
            InvocationTargetException, ClassNotFoundException, NoSuchMethodException,
            InstantiationException, NoSuchAlgorithmException, NoSuchPaddingException {
        FileMockContext mockContext = new FileMockContext(getContext());
        AuthenticationContext context = new AuthenticationContext(mockContext, VALID_AUTHORITY,
                false);
        Method m = ReflectionUtils.getTestMethod(context, "resolveIntent", Intent.class);
        Intent intent = new Intent();
        intent.setClass(mockContext, AuthenticationActivity.class);

        boolean actual = (Boolean)m.invoke(context, intent);
        assertTrue("Intent is expected to resolve", actual);

        mockContext.resolveIntent = false;
        actual = (Boolean)m.invoke(context, intent);
        assertFalse("Intent is not expected to resolve", actual);
    }

    /**
     * Test throws for different missing arguments
     * 
     * @throws NoSuchPaddingException
     * @throws NoSuchAlgorithmException
     */
    @SmallTest
    public void testAcquireTokenNegativeArguments() throws NoSuchAlgorithmException,
            NoSuchPaddingException {
        FileMockContext mockContext = new FileMockContext(getContext());
        final AuthenticationContext context = new AuthenticationContext(mockContext,
                VALID_AUTHORITY, false);
        final MockActivity testActivity = new MockActivity();
        final MockAuthenticationCallback testEmptyCallback = new MockAuthenticationCallback();

        AssertUtils.assertThrowsException(IllegalArgumentException.class, "callback",
                new Runnable() {

                    @Override
                    public void run() {
                        context.acquireToken(testActivity, "resource", "clientId", "redirectUri",
                                "userid", null);
                    }
                });

        AssertUtils.assertThrowsException(IllegalArgumentException.class, "activity",
                new Runnable() {

                    @Override
                    public void run() {
                        context.acquireToken(null, "resource", "clientId", "redirectUri", "userid",
                                testEmptyCallback);
                    }
                });

        AssertUtils.assertThrowsException(IllegalArgumentException.class, "resource",
                new Runnable() {

                    @Override
                    public void run() {
                        context.acquireToken(testActivity, null, "clientId", "redirectUri",
                                "userid", testEmptyCallback);
                    }
                });

        AssertUtils.assertThrowsException(IllegalArgumentException.class, "resource",
                new Runnable() {

                    @Override
                    public void run() {
                        context.acquireToken(testActivity, "", "clientId", "redirectUri", "userid",
                                testEmptyCallback);
                    }
                });

        AssertUtils.assertThrowsException(IllegalArgumentException.class, "clientid",
                new Runnable() {

                    @Override
                    public void run() {
                        context.acquireToken(testActivity, "resource", null, "redirectUri",
                                "userid", testEmptyCallback);
                    }
                });
    }

    @SmallTest
    public void testAcquireToken_userId() throws ClassNotFoundException, IllegalArgumentException,
            NoSuchFieldException, IllegalAccessException, NoSuchAlgorithmException,
            NoSuchPaddingException {
        FileMockContext mockContext = new FileMockContext(getContext());
        final AuthenticationContext context = new AuthenticationContext(mockContext,
                "https://login.windows.net/common", false);
        final MockActivity testActivity = new MockActivity();
        final CountDownLatch signal = new CountDownLatch(1);
        MockAuthenticationCallback callback = new MockAuthenticationCallback(signal);
        testActivity.mSignal = signal;

        context.acquireToken(testActivity, "resource56", "clientId345", "redirect123", "userid123",
                callback);

        // verify request
        Intent intent = testActivity.mStartActivityIntent;
        assertNotNull(intent);
        Serializable request = intent
                .getSerializableExtra(AuthenticationConstants.Browser.REQUEST_MESSAGE);
        assertEquals("AuthenticationRequest inside the intent", request.getClass(),
                Class.forName("com.microsoft.adal.AuthenticationRequest"));
        String redirect = (String)ReflectionUtils.getFieldValue(request, "mRedirectUri");
        assertEquals("Redirect uri is same as package", "redirect123", redirect);
        String loginHint = (String)ReflectionUtils.getFieldValue(request, "mLoginHint");
        assertEquals("login hint same as userid", "userid123", loginHint);
        String client = (String)ReflectionUtils.getFieldValue(request, "mClientId");
        assertEquals("client is same", "clientId345", client);
        String authority = (String)ReflectionUtils.getFieldValue(request, "mAuthority");
        assertEquals("authority is same", "https://login.windows.net/common", authority);
        String resource = (String)ReflectionUtils.getFieldValue(request, "mResource");
        assertEquals("resource is same", "resource56", resource);
    }

    @SmallTest
    public void testEmptyRedirect() throws ClassNotFoundException, IllegalArgumentException,
            NoSuchFieldException, IllegalAccessException, NoSuchAlgorithmException,
            NoSuchPaddingException {
        FileMockContext mockContext = new FileMockContext(getContext());
        final AuthenticationContext context = new AuthenticationContext(mockContext,
                "https://login.windows.net/common", false);
        final MockActivity testActivity = new MockActivity();
        final CountDownLatch signal = new CountDownLatch(1);
        MockAuthenticationCallback callback = new MockAuthenticationCallback(signal);
        testActivity.mSignal = signal;

        context.acquireToken(testActivity, "resource", "clientId", "", "userid", callback);

        Intent intent = testActivity.mStartActivityIntent;
        assertNotNull(intent);
        Serializable request = intent
                .getSerializableExtra(AuthenticationConstants.Browser.REQUEST_MESSAGE);
        assertEquals("AuthenticationRequest inside the intent", request.getClass(),
                Class.forName("com.microsoft.adal.AuthenticationRequest"));
        String redirect = (String)ReflectionUtils.getFieldValue(request, "mRedirectUri");
        assertEquals("Redirect uri is same as package", "com.microsoft.adal.testapp", redirect);
    }

    @SmallTest
    public void testPrompt() throws IllegalArgumentException, NoSuchFieldException,
            IllegalAccessException, ClassNotFoundException, NoSuchAlgorithmException,
            NoSuchPaddingException {
        FileMockContext mockContext = new FileMockContext(getContext());
        final AuthenticationContext context = new AuthenticationContext(mockContext,
                "https://login.windows.net/common", false);
        final MockActivity testActivity = new MockActivity();
        final CountDownLatch signal = new CountDownLatch(1);
        MockAuthenticationCallback callback = new MockAuthenticationCallback(signal);
        testActivity.mSignal = signal;

        // 1 - Send prompt always
        context.acquireToken(testActivity, "testExtraParamsResource", "testExtraParamsClientId",
                "testExtraParamsredirectUri", PromptBehavior.Always, callback);

        // get intent from activity to verify extraparams are send
        Intent intent = testActivity.mStartActivityIntent;
        assertNotNull(intent);
        Serializable request = intent
                .getSerializableExtra(AuthenticationConstants.Browser.REQUEST_MESSAGE);

        PromptBehavior prompt = (PromptBehavior)ReflectionUtils.getFieldValue(request, "mPrompt");
        assertEquals("Prompt param is same", PromptBehavior.Always, prompt);
    }

    @SmallTest
    public void testExtraParams() throws IllegalArgumentException, NoSuchFieldException,
            IllegalAccessException, ClassNotFoundException, NoSuchAlgorithmException,
            NoSuchPaddingException {
        FileMockContext mockContext = new FileMockContext(getContext());
        final AuthenticationContext context = new AuthenticationContext(mockContext,
                "https://login.windows.net/common", false);
        final MockActivity testActivity = new MockActivity();
        final CountDownLatch signal = new CountDownLatch(1);
        String expected = "&extraParam=1";
        MockAuthenticationCallback callback = new MockAuthenticationCallback(signal);
        testActivity.mSignal = signal;

        // 1 - Send extra param
        context.acquireToken(testActivity, "testExtraParamsResource", "testExtraParamsClientId",
                "testExtraParamsredirectUri", PromptBehavior.Always, expected, callback);

        // get intent from activity to verify extraparams are send
        Intent intent = testActivity.mStartActivityIntent;
        assertNotNull(intent);
        Serializable request = intent
                .getSerializableExtra(AuthenticationConstants.Browser.REQUEST_MESSAGE);

        assertEquals("AuthenticationRequest inside the intent", request.getClass(),
                Class.forName("com.microsoft.adal.AuthenticationRequest"));
        String extraparm = (String)ReflectionUtils.getFieldValue(request,
                "mExtraQueryParamsAuthentication");
        assertEquals("Extra query param is same", expected, extraparm);

        // 2- Don't send extraqueryparam
        ReflectionUtils.setFieldValue(context, "mAuthorizationCallback", null);
        context.acquireToken(testActivity, "testExtraParamsResource", "testExtraParamsClientId",
                "testExtraParamsredirectUri", PromptBehavior.Always, null, callback);

        // verify from mocked activity intent
        intent = testActivity.mStartActivityIntent;
        assertNotNull(intent);
        request = intent.getSerializableExtra(AuthenticationConstants.Browser.REQUEST_MESSAGE);

        assertEquals("AuthenticationRequest inside the intent", request.getClass(),
                Class.forName("com.microsoft.adal.AuthenticationRequest"));
        extraparm = (String)ReflectionUtils.getFieldValue(request,
                "mExtraQueryParamsAuthentication");
        assertNull("Extra query param is null", extraparm);
    }

    public static Object createAuthenticationRequest(String authority, String resource,
            String client, String redirect, String loginhint) throws ClassNotFoundException,
            NoSuchMethodException, IllegalArgumentException, InstantiationException,
            IllegalAccessException, InvocationTargetException {

        Class<?> c = Class.forName("com.microsoft.adal.AuthenticationRequest");

        Constructor<?> constructor = c.getDeclaredConstructor(String.class, String.class,
                String.class, String.class, String.class);
        constructor.setAccessible(true);
        Object o = constructor.newInstance(authority, resource, client, redirect, loginhint);
        return o;
    }

    /**
     * Test throws for different missing arguments
     * 
     * @throws NoSuchPaddingException
     * @throws NoSuchAlgorithmException
     */
    @SmallTest
    public void testAcquireTokenByRefreshTokenNegativeArguments() throws NoSuchAlgorithmException,
            NoSuchPaddingException {
        FileMockContext mockContext = new FileMockContext(getContext());

        // AuthenticationContext will throw at constructor if authority is null.

        final AuthenticationContext context = new AuthenticationContext(mockContext,
                VALID_AUTHORITY, false);
        final MockAuthenticationCallback mockCallback = new MockAuthenticationCallback();

        // null callback
        AssertUtils.assertThrowsException(IllegalArgumentException.class, "callback",
                new Runnable() {

                    @Override
                    public void run() {
                        context.acquireTokenByRefreshToken("refresh", "clientId", "resource", null);
                    }
                });

        AssertUtils.assertThrowsException(IllegalArgumentException.class, "callback",
                new Runnable() {

                    @Override
                    public void run() {
                        context.acquireTokenByRefreshToken("refresh", "clientId", null);
                    }
                });

        // null refresh token
        AssertUtils.assertThrowsException(IllegalArgumentException.class, "refresh",
                new Runnable() {

                    @Override
                    public void run() {
                        context.acquireTokenByRefreshToken(null, "clientId", "resource",
                                mockCallback);
                    }
                });

        AssertUtils.assertThrowsException(IllegalArgumentException.class, "refresh",
                new Runnable() {

                    @Override
                    public void run() {
                        context.acquireTokenByRefreshToken(null, "clientId", mockCallback);
                    }
                });

        // null clientiD
        AssertUtils.assertThrowsException(IllegalArgumentException.class, "clientid",
                new Runnable() {

                    @Override
                    public void run() {
                        context.acquireTokenByRefreshToken("refresh", null, "resource",
                                mockCallback);
                    }
                });

        AssertUtils.assertThrowsException(IllegalArgumentException.class, "clientid",
                new Runnable() {

                    @Override
                    public void run() {
                        context.acquireTokenByRefreshToken("refresh", null, mockCallback);
                    }
                });
    }
   
    @SmallTest
    public void testAcquireTokenByRefreshToken_ConnectionNotAvailable()
            throws NoSuchFieldException, IllegalArgumentException, IllegalAccessException,
            NoSuchAlgorithmException, NoSuchPaddingException {
        FileMockContext mockContext = new FileMockContext(getContext());
        final AuthenticationContext context = new AuthenticationContext(mockContext,
                VALID_AUTHORITY, false);
        setConnectionAvailable(context, false);

        final MockAuthenticationCallback mockCallback = new MockAuthenticationCallback();
        context.acquireTokenByRefreshToken("refresh", "clientId", "resource", mockCallback);
        assertTrue("Exception type", mockCallback.mException instanceof AuthenticationException);
        assertEquals("Connection related error code", ADALError.DEVICE_CONNECTION_IS_NOT_AVAILABLE,
                ((AuthenticationException)mockCallback.mException).getCode());
    }

    private void setConnectionAvailable(final AuthenticationContext context, final boolean status)
            throws NoSuchFieldException, IllegalAccessException {
        ReflectionUtils.setFieldValue(context, "mConnectionService", new IConnectionService() {

            @Override
            public boolean isConnectionAvailable() {
                return status;
            }
        });
    }

    /**
     * Test throws for different missing arguments
     * 
     * @throws IllegalAccessException
     * @throws NoSuchFieldException
     * @throws IllegalArgumentException
     * @throws InvocationTargetException
     * @throws InstantiationException
     * @throws NoSuchMethodException
     * @throws ClassNotFoundException
     * @throws NoSuchPaddingException
     * @throws NoSuchAlgorithmException
     */
    @SmallTest
    public void testAcquireTokenByRefreshTokenPositive() throws IllegalArgumentException,
            NoSuchFieldException, IllegalAccessException, ClassNotFoundException,
            NoSuchMethodException, InstantiationException, InvocationTargetException,
            NoSuchAlgorithmException, NoSuchPaddingException {
        FileMockContext mockContext = new FileMockContext(getContext());
        ITokenCacheStore mockCache = getCacheForRefreshToken();

        final AuthenticationContext context = new AuthenticationContext(mockContext,
                VALID_AUTHORITY, false, mockCache);
        setConnectionAvailable(context, true);
        final MockActivity testActivity = new MockActivity();
        final CountDownLatch signal = new CountDownLatch(1);
        String expectedAccessToken = "TokenFortestAcquireToken" + UUID.randomUUID().toString();
        String expectedClientId = "client" + UUID.randomUUID().toString();
        String exptedResource = "resource" + UUID.randomUUID().toString();
        testActivity.mSignal = signal;
        MockAuthenticationCallback callback = new MockAuthenticationCallback(signal);

        MockWebRequestHandler mockWebRequest = setMockWebRequest(context, expectedAccessToken);

        context.acquireTokenByRefreshToken("refreshTokenSending", expectedClientId, callback);

        // Verify that new refresh token is matching to mock response
        assertEquals("Same token", expectedAccessToken, callback.mResult.getAccessToken());
        assertEquals("Same refresh token", "refreshToken=", callback.mResult.getRefreshToken());
        assertTrue("Content has client in the message", mockWebRequest.getRequestContent()
                .contains(expectedClientId));
        assertFalse("Content does not have resource in the message", mockWebRequest
                .getRequestContent().contains(exptedResource));

        context.acquireTokenByRefreshToken("refreshTokenSending", expectedClientId, exptedResource,
                callback);

        // Verify that new refresh token is matching to mock response
        assertEquals("Same token", expectedAccessToken, callback.mResult.getAccessToken());
        assertEquals("Same refresh token", "refreshToken=", callback.mResult.getRefreshToken());
        assertTrue("Content has client in the message", mockWebRequest.getRequestContent()
                .contains(expectedClientId));
        assertTrue("Content has resource in the message", mockWebRequest.getRequestContent()
                .contains(exptedResource));
    }

    private MockWebRequestHandler setMockWebRequest(final AuthenticationContext context,
            String expectedAccessToken) throws NoSuchFieldException, IllegalAccessException {
        MockWebRequestHandler mockWebRequest = new MockWebRequestHandler();
        String json = "{\"access_token\":\""
                + expectedAccessToken
                + "\",\"token_type\":\"Bearer\",\"expires_in\":\"29344\",\"expires_on\":\"1368768616\",\"refresh_token\":\"refreshToken=\",\"scope\":\"*\"}";
        mockWebRequest.setReturnResponse(new HttpWebResponse(200, json.getBytes(Charset
                .defaultCharset()), null));
        ReflectionUtils.setFieldValue(context, "mWebRequest", mockWebRequest);
        return mockWebRequest;
    }

    private void removeMockWebRequest(final AuthenticationContext context)
            throws NoSuchFieldException, IllegalAccessException {
        ReflectionUtils.setFieldValue(context, "mWebRequest", null);
    }

    /**
     * authority is malformed and error should come back in callback
     * 
     * @throws InterruptedException
     * @throws NoSuchPaddingException
     * @throws NoSuchAlgorithmException
     */
    @SmallTest
    public void testAcquireTokenAuthorityMalformed() throws InterruptedException,
            NoSuchAlgorithmException, NoSuchPaddingException {
        // Malformed url error will come back in callback
        FileMockContext mockContext = new FileMockContext(getContext());
        final AuthenticationContext context = new AuthenticationContext(mockContext,
                "abcd://vv../v", false);
        final MockActivity testActivity = new MockActivity();
        final CountDownLatch signal = new CountDownLatch(1);
        MockAuthenticationCallback callback = new MockAuthenticationCallback(signal);

        context.acquireToken(testActivity, "resource", "clientId", "redirectUri", "userid",
                callback);
        signal.await(CONTEXT_REQUEST_TIME_OUT, TimeUnit.MILLISECONDS);

        // Check response in callback result
        assertNotNull("Error is not null", callback.mException);
        assertEquals("NOT_VALID_URL", ADALError.DEVELOPER_AUTHORITY_IS_NOT_VALID_URL,
                ((AuthenticationException)callback.mException).getCode());
    }

    /**
     * authority is validated and intent start request is sent
     * 
     * @throws InterruptedException
     * @throws IllegalArgumentException
     * @throws NoSuchFieldException
     * @throws IllegalAccessException
     * @throws NoSuchPaddingException
     * @throws NoSuchAlgorithmException
     */
    @SmallTest
    public void testAcquireTokenValidateAuthorityReturnsValid() throws InterruptedException,
            IllegalArgumentException, NoSuchFieldException, IllegalAccessException,
            NoSuchAlgorithmException, NoSuchPaddingException {

        FileMockContext mockContext = new FileMockContext(getContext());
        AuthenticationContext context = new AuthenticationContext(mockContext, VALID_AUTHORITY,
                true);
        final CountDownLatch signal = new CountDownLatch(1);
        MockActivity testActivity = new MockActivity();
        testActivity.mSignal = signal;
        MockAuthenticationCallback callback = new MockAuthenticationCallback(signal);
        MockDiscovery discovery = new MockDiscovery(true);
        ReflectionUtils.setFieldValue(context, "mDiscovery", discovery);

        context.acquireToken(testActivity, "resource", "clientid", "redirectUri", "userid",
                callback);
        signal.await(CONTEXT_REQUEST_TIME_OUT, TimeUnit.MILLISECONDS);

        // Check response in callback result
        assertNull("Error is null", callback.mException);
        assertEquals("Activity was attempted to start with request code",
                AuthenticationConstants.UIRequest.BROWSER_FLOW,
                testActivity.mStartActivityRequestCode);
    }

    /**
     * Invalid authority returns
     * 
     * @throws InterruptedException
     * @throws IllegalArgumentException
     * @throws NoSuchFieldException
     * @throws IllegalAccessException
     * @throws NoSuchPaddingException
     * @throws NoSuchAlgorithmException
     */
    @SmallTest
    public void testAcquireTokenValidateAuthorityReturnsInValid() throws InterruptedException,
            IllegalArgumentException, NoSuchFieldException, IllegalAccessException,
            NoSuchAlgorithmException, NoSuchPaddingException {

        FileMockContext mockContext = new FileMockContext(getContext());
        final AuthenticationContext context = new AuthenticationContext(mockContext,
                VALID_AUTHORITY, true);
        final MockActivity testActivity = new MockActivity();
        final CountDownLatch signal = new CountDownLatch(1);
        MockAuthenticationCallback callback = new MockAuthenticationCallback(signal);
        MockDiscovery discovery = new MockDiscovery(false);
        ReflectionUtils.setFieldValue(context, "mDiscovery", discovery);

        context.acquireToken(testActivity, "resource", "clientid", "redirectUri", "userid",
                callback);
        signal.await(CONTEXT_REQUEST_TIME_OUT, TimeUnit.MILLISECONDS);

        // Check response in callback result
        assertNotNull("Error is not null", callback.mException);
        assertEquals("NOT_VALID_URL", ADALError.DEVELOPER_AUTHORITY_IS_NOT_VALID_INSTANCE,
                ((AuthenticationException)callback.mException).getCode());
        assertTrue(
                "Activity was not attempted to start with request code",
                AuthenticationConstants.UIRequest.BROWSER_FLOW != testActivity.mStartActivityRequestCode);
        clearCache(context);
    }

    /**
     * acquire token without validation
     * 
     * @throws InterruptedException
     * @throws IllegalArgumentException
     * @throws NoSuchFieldException
     * @throws IllegalAccessException
     * @throws NoSuchPaddingException
     * @throws NoSuchAlgorithmException
     */
    @SmallTest
    public void testAcquireTokenWithoutValidation() throws InterruptedException,
            IllegalArgumentException, NoSuchFieldException, IllegalAccessException,
            NoSuchAlgorithmException, NoSuchPaddingException {

        FileMockContext mockContext = new FileMockContext(getContext());
        final AuthenticationContext context = new AuthenticationContext(mockContext,
                VALID_AUTHORITY, false);
        final MockActivity testActivity = new MockActivity();
        final CountDownLatch signal = new CountDownLatch(1);
        testActivity.mSignal = signal;
        MockAuthenticationCallback callback = new MockAuthenticationCallback(signal);

        context.acquireToken(testActivity, "resource", "clientid", "redirectUri", "userid",
                callback);
        signal.await(CONTEXT_REQUEST_TIME_OUT, TimeUnit.MILLISECONDS);

        // Check response in callback result
        assertNull("Error is null", callback.mException);
        assertEquals("Activity was attempted to start with request code",
                AuthenticationConstants.UIRequest.BROWSER_FLOW,
                testActivity.mStartActivityRequestCode);
        clearCache(context);
    }

    /**
     * acquire token uses refresh token, but web request returns error
     * 
     * @throws InterruptedException
     * @throws IllegalArgumentException
     * @throws NoSuchFieldException
     * @throws IllegalAccessException
     * @throws InvocationTargetException
     * @throws InstantiationException
     * @throws NoSuchMethodException
     * @throws ClassNotFoundException
     * @throws NoSuchPaddingException
     * @throws NoSuchAlgorithmException
     */
    @SmallTest
    public void testRefreshTokenWebRequestHasError() throws InterruptedException,
            IllegalArgumentException, NoSuchFieldException, IllegalAccessException,
            ClassNotFoundException, NoSuchMethodException, InstantiationException,
            InvocationTargetException, NoSuchAlgorithmException, NoSuchPaddingException {

        FileMockContext mockContext = new FileMockContext(getContext());
        ITokenCacheStore mockCache = getCacheForRefreshToken();
        final AuthenticationContext context = new AuthenticationContext(mockContext,
                VALID_AUTHORITY, false, mockCache);
        setConnectionAvailable(context, true);
        final CountDownLatch signal = new CountDownLatch(1);
        final MockActivity testActivity = new MockActivity(signal);
        MockAuthenticationCallback callback = new MockAuthenticationCallback(signal);
        MockWebRequestHandler webrequest = new MockWebRequestHandler();
        webrequest.setReturnResponse(new HttpWebResponse(503, null, null));
        ReflectionUtils.setFieldValue(context, "mWebRequest", webrequest);
        final TestLogResponse response = new TestLogResponse();
        listenForLogMessage(response, "Refresh token did not return accesstoken.");

        context.acquireToken(testActivity, "resource", "clientid", "redirectUri", "userid",
                PromptBehavior.Never, null, callback);
        signal.await(CONTEXT_REQUEST_TIME_OUT, TimeUnit.MILLISECONDS);

        // Check response in callback result
        assertNotNull("Error is not null", callback.mException);
        assertTrue("Log message has same webstatus code",
                response.additionalMessage.contains("503"));
        assertNull("Cache is empty for this item", mockCache.getItem(CacheKey.createCacheKey(
                VALID_AUTHORITY, "resource", "clientId", false, "userid")));
        assertNull("Cache is empty for this item", mockCache.getItem(CacheKey.createCacheKey(
                VALID_AUTHORITY, "resource", "clientId", true, "userid")));
        clearCache(context);
    }

    /**
     * acquire token using refresh token. All web calls are mocked. Refresh
     * token response must match to result and cache.
     * 
     * @throws InterruptedException
     * @throws IllegalArgumentException
     * @throws NoSuchFieldException
     * @throws IllegalAccessException
     * @throws InvocationTargetException
     * @throws InstantiationException
     * @throws NoSuchMethodException
     * @throws ClassNotFoundException
     * @throws NoSuchPaddingException
     * @throws NoSuchAlgorithmException
     */
    @SmallTest
    public void testRefreshTokenPositive() throws InterruptedException, IllegalArgumentException,
            NoSuchFieldException, IllegalAccessException, ClassNotFoundException,
            NoSuchMethodException, InstantiationException, InvocationTargetException,
            NoSuchAlgorithmException, NoSuchPaddingException {

        FileMockContext mockContext = new FileMockContext(getContext());
        ITokenCacheStore mockCache = getCacheForRefreshToken();

        final AuthenticationContext context = new AuthenticationContext(mockContext,
                VALID_AUTHORITY, false, mockCache);
        setConnectionAvailable(context, true);
        final MockActivity testActivity = new MockActivity();
        final CountDownLatch signal = new CountDownLatch(1);
        testActivity.mSignal = signal;
        MockAuthenticationCallback callback = new MockAuthenticationCallback(signal);
        MockWebRequestHandler webrequest = new MockWebRequestHandler();
        String json = "{\"access_token\":\"TokenFortestRefreshTokenPositive\",\"token_type\":\"Bearer\",\"expires_in\":\"28799\",\"expires_on\":\"1368768616\",\"refresh_token\":\"refresh112\",\"scope\":\"*\"}";
        webrequest.setReturnResponse(new HttpWebResponse(200, json.getBytes(Charset
                .defaultCharset()), null));
        ReflectionUtils.setFieldValue(context, "mWebRequest", webrequest);

        // call acquire token which will try refresh token based on cache
        context.acquireToken(testActivity, "resource", "clientid", "redirectUri", "userid",
                callback);
        signal.await(CONTEXT_REQUEST_TIME_OUT, TimeUnit.MILLISECONDS);

        // check response in callback
        assertNull("Error is null", callback.mException);
        assertEquals("Token is same", "TokenFortestRefreshTokenPositive",
                callback.mResult.getAccessToken());
        assertNotNull("Cache is NOT empty for this userid for regular token",
                mockCache.getItem(CacheKey.createCacheKey(VALID_AUTHORITY, "resource", "clientId",
                        false, "userid")));
        assertNull("Cache is empty for multiresource token", mockCache.getItem(CacheKey
                .createCacheKey(VALID_AUTHORITY, "resource", "clientId", true, "userid")));
        clearCache(context);
    }

    /**
     * authority and resource are case insensitive. Cache lookup will return
     * item from cache.
     * 
     * @throws InterruptedException
     * @throws IllegalArgumentException
     * @throws NoSuchFieldException
     * @throws IllegalAccessException
     * @throws NoSuchPaddingException
     * @throws NoSuchAlgorithmException
     */
    @SmallTest
    public void testAcquireTokenCacheLookup() throws InterruptedException,
            IllegalArgumentException, NoSuchFieldException, IllegalAccessException,
            NoSuchAlgorithmException, NoSuchPaddingException {

        FileMockContext mockContext = new FileMockContext(getContext());
        String tokenToTest = "accessToken=" + UUID.randomUUID();
        String resource = "Resource" + UUID.randomUUID();
        ITokenCacheStore mockCache = new DefaultTokenCacheStore(mockContext);
        mockCache.removeAll();
        addItemToCache(mockCache, tokenToTest, "refreshToken", VALID_AUTHORITY, resource,
                "clientId", "userId", false);

        final AuthenticationContext context = new AuthenticationContext(mockContext,
                VALID_AUTHORITY, false, mockCache);
        final MockActivity testActivity = new MockActivity();
        final CountDownLatch signal = new CountDownLatch(1);
        testActivity.mSignal = signal;
        MockAuthenticationCallback callback = new MockAuthenticationCallback(signal);

        // acquire token call will return from cache
        context.acquireToken(testActivity, resource, "ClienTid", "redirectUri", "userid", callback);
        signal.await(CONTEXT_REQUEST_TIME_OUT, TimeUnit.MILLISECONDS);

        // Check response in callback
        assertNull("Error is null", callback.mException);
        assertEquals("Same token in response as in cache", tokenToTest,
                callback.mResult.getAccessToken());
        clearCache(context);
    }

    /**
     * setup cache with userid for normal token and multiresource refresh token
     * bound to one userid. test calls for different resources and users.
     * 
     * @throws InterruptedException
     * @throws IllegalArgumentException
     * @throws NoSuchFieldException
     * @throws IllegalAccessException
     * @throws NoSuchPaddingException
     * @throws NoSuchAlgorithmException
     */
    @SmallTest
    public void testAcquireTokenMultiResourceToken_UserId() throws InterruptedException,
            IllegalArgumentException, NoSuchFieldException, IllegalAccessException,
            NoSuchAlgorithmException, NoSuchPaddingException {

        FileMockContext mockContext = new FileMockContext(getContext());
        String tokenToTest = "accessToken=" + UUID.randomUUID();
        String tokenWithRefreshToken = "accessToken=" + UUID.randomUUID();
        String resource = "Resource" + UUID.randomUUID();

        ITokenCacheStore mockCache = new DefaultTokenCacheStore(mockContext);
        mockCache.removeAll();
        addItemToCache(mockCache, tokenToTest, "refreshTokenNormal", VALID_AUTHORITY, resource,
                "ClienTid", "userid", false);
        addItemToCache(mockCache, "", "refreshTokenMultiResource", VALID_AUTHORITY, resource,
                "ClienTid", "userid", true);
        addItemToCache(mockCache, "", "refreshTokenMultiResource2", VALID_AUTHORITY,
                "dummyResource2", "ClienTid", "userid", true);
        final AuthenticationContext context = new AuthenticationContext(mockContext,
                VALID_AUTHORITY, false, mockCache);
        setConnectionAvailable(context, true);
        MockWebRequestHandler mockWebRequest = setMockWebRequest(context, tokenWithRefreshToken);

        CountDownLatch signal = new CountDownLatch(1);
        MockActivity testActivity = new MockActivity(signal);
        MockAuthenticationCallback callback = new MockAuthenticationCallback(signal);

        // -----------Acquire token call will return from
        // cache--------------------
        context.acquireToken(testActivity, resource, "ClienTid", "redirectUri", "userid", callback);
        signal.await(CONTEXT_REQUEST_TIME_OUT, TimeUnit.MILLISECONDS);

        assertNull("Error is null", callback.mException);
        assertEquals("Same token in response as in cache", tokenToTest,
                callback.mResult.getAccessToken());

        // -----------Acquire token call will not return from cache for broad
        // Token-
        // cached item does not have access token since it was broad refresh
        // token
        signal = new CountDownLatch(1);
        callback = new MockAuthenticationCallback(signal);
        context.acquireToken(testActivity, "dummyResource2", "ClienTid", "redirectUri", "userid",
                callback);
        signal.await(CONTEXT_REQUEST_TIME_OUT, TimeUnit.MILLISECONDS);

        assertNull("Error is null", callback.mException);
        assertEquals("Same token as refresh token result", tokenWithRefreshToken,
                callback.mResult.getAccessToken());

        // -----------Different resource with same
        // userid--------------------------
        signal = new CountDownLatch(1);
        testActivity = new MockActivity(signal);
        callback = new MockAuthenticationCallback(signal);
        context.acquireToken(testActivity, "anotherResource123", "ClienTid", "redirectUri",
                "userid", callback);
        signal.await(CONTEXT_REQUEST_TIME_OUT, TimeUnit.MILLISECONDS);

        assertEquals("Token is returned from refresh token request", tokenWithRefreshToken,
                callback.mResult.getAccessToken());
        assertFalse("Multiresource is not set in the mocked response",
                callback.mResult.getIsMultiResourceRefreshToken());
        assertTrue("Request to get token uses broad refresh token", mockWebRequest
                .getRequestContent().contains("refreshTokenMultiResource"));

        // ----------Same call again to use it from
        // cache----------------------------
        callback.mResult = null;
        removeMockWebRequest(context);
        context.acquireToken(testActivity, "anotherResource123", "ClienTid", "redirectUri",
                "userid", callback);
        assertEquals("Same token in response as in cache for same call", tokenWithRefreshToken,
                callback.mResult.getAccessToken());

        // -----------Empty userid will
        // prompt---------------------------------------
        // Items are linked to userid. If it is not there, it can't use for
        // refresh or access token.
        signal = new CountDownLatch(1);
        testActivity = new MockActivity(signal);
        callback = new MockAuthenticationCallback(signal);
        context.acquireToken(testActivity, resource, "ClienTid", "redirectUri", "", callback);
        signal.await(CONTEXT_REQUEST_TIME_OUT, TimeUnit.MILLISECONDS);

        assertNull("Result is null since it tries to start activity", callback.mResult);
        assertEquals("ACtivity was attempted to start.", UIRequest.BROWSER_FLOW,
                testActivity.mStartActivityRequestCode);

        clearCache(context);
    }

    @SmallTest
    public void testAcquireTokenMultiResource_ADFSIssue() throws InterruptedException,
            IllegalArgumentException, NoSuchFieldException, IllegalAccessException,
            NoSuchAlgorithmException, NoSuchPaddingException {
        // adfs does not return userid and multiresource token
        FileMockContext mockContext = new FileMockContext(getContext());
        String tokenToTest = "accessToken=" + UUID.randomUUID();
        String resource = "Resource" + UUID.randomUUID();
        ITokenCacheStore mockCache = new DefaultTokenCacheStore(mockContext);
        // add item without userid and normal refresh token
        addItemToCache(mockCache, tokenToTest, "refreshToken", VALID_AUTHORITY, resource,
                "ClienTid", "", false);
        final AuthenticationContext context = new AuthenticationContext(mockContext,
                VALID_AUTHORITY, false, mockCache);
        MockActivity testActivity = new MockActivity();
        CountDownLatch signal = new CountDownLatch(1);
        testActivity.mSignal = signal;
        MockAuthenticationCallback callback = new MockAuthenticationCallback(signal);

        // acquire token call will return from cache
        context.acquireToken(testActivity, resource, "clientid", "redirectUri", "", callback);
        signal.await(CONTEXT_REQUEST_TIME_OUT, TimeUnit.MILLISECONDS);

        // Check response in callback
        assertNull("Error is null", callback.mException);
        assertEquals("Same token in response as in cache", tokenToTest,
                callback.mResult.getAccessToken());

        // Request with different resource will result in prompt
        signal = new CountDownLatch(1);
        testActivity = new MockActivity();
        testActivity.mSignal = signal;
        callback = new MockAuthenticationCallback(signal);
        context.acquireToken(testActivity, "anotherResource123", "ClienTid", "redirectUri", "",
                callback);
        signal.await(CONTEXT_REQUEST_TIME_OUT, TimeUnit.MILLISECONDS);

        assertTrue("Attemps to launch", testActivity.mStartActivityRequestCode != -1);

        // asking with different userid will not return item from cache and try
        // to launch activity
        signal = new CountDownLatch(1);
        testActivity = new MockActivity();
        testActivity.mSignal = signal;
        callback = new MockAuthenticationCallback(signal);
        context.acquireToken(testActivity, resource, "ClienTid", "redirectUri", "someuser",
                callback);
        signal.await(CONTEXT_REQUEST_TIME_OUT, TimeUnit.MILLISECONDS);
        assertTrue("Attemps to launch", testActivity.mStartActivityRequestCode != -1);

        clearCache(context);
    }

    private void listenForLogMessage(final TestLogResponse response, final String msg) {
        Logger.getInstance().setExternalLogger(new ILogger() {

            @Override
            public void Log(String tag, String message, String additionalMessage, LogLevel level,
                    ADALError errorCode) {

                if (message == msg) {

                    response.tag = tag;
                    response.message = message;
                    response.additionalMessage = additionalMessage;
                    response.level = level;
                    response.errorCode = errorCode;
                }
            }
        });
    }

    private ITokenCacheStore getCacheForRefreshToken() throws NoSuchAlgorithmException,
            NoSuchPaddingException {
        DefaultTokenCacheStore cache = new DefaultTokenCacheStore(getContext());
        Calendar expiredTime = new GregorianCalendar();
        Log.d("Test", "Time now:" + expiredTime.toString());
        expiredTime.add(Calendar.MINUTE, -60);
        TokenCacheItem refreshItem = new TokenCacheItem();
        refreshItem.setAuthority(VALID_AUTHORITY);
        refreshItem.setResource("resource");
        refreshItem.setClientId("clientId");
        refreshItem.setAccessToken("accessToken");
        refreshItem.setRefreshToken("refreshToken=");
        refreshItem.setExpiresOn(expiredTime.getTime());
        cache.setItem(
                CacheKey.createCacheKey(VALID_AUTHORITY, "resource", "clientId", false, "userId"),
                refreshItem);
        return cache;
    }

    private ITokenCacheStore getMockCache(int minutes, String token, String resource,
            String client, String user, boolean isMultiResource) throws NoSuchAlgorithmException,
            NoSuchPaddingException {
        DefaultTokenCacheStore cache = new DefaultTokenCacheStore(getContext());
        // Code response
        Calendar timeAhead = new GregorianCalendar();
        Log.d("Test", "Time now:" + timeAhead.toString());
        timeAhead.add(Calendar.MINUTE, minutes);
        TokenCacheItem refreshItem = new TokenCacheItem();
        refreshItem.setAuthority(VALID_AUTHORITY);
        refreshItem.setResource(resource);
        refreshItem.setClientId(client);
        refreshItem.setAccessToken(token);
        refreshItem.setRefreshToken("refreshToken=");
        refreshItem.setExpiresOn(timeAhead.getTime());
        cache.setItem(
                CacheKey.createCacheKey(VALID_AUTHORITY, resource, client, isMultiResource, user),
                refreshItem);
        return cache;
    }

    private ITokenCacheStore addItemToCache(ITokenCacheStore cache, String token,
            String refreshToken, String authority, String resource, String clientId, String userId,
            boolean isMultiResource) {
        // Code response
        Calendar timeAhead = new GregorianCalendar();
        Log.d(TAG, "addItemToCache Time now:" + timeAhead.toString());
        timeAhead.add(Calendar.MINUTE, 10);
        TokenCacheItem refreshItem = new TokenCacheItem();
        refreshItem.setAuthority(authority);
        refreshItem.setResource(resource);
        refreshItem.setClientId(clientId);
        refreshItem.setAccessToken(token);
        refreshItem.setRefreshToken(refreshToken);
        refreshItem.setExpiresOn(timeAhead.getTime());
        refreshItem.setIsMultiResourceRefreshToken(isMultiResource);
        String key = CacheKey.createCacheKey(VALID_AUTHORITY, resource, clientId, isMultiResource,
                userId);
        Log.d(TAG, "Key: " + key);
        cache.setItem(key, refreshItem);

        TokenCacheItem item = cache.getItem(CacheKey.createCacheKey(VALID_AUTHORITY, resource,
                clientId, isMultiResource, userId));
        assertNotNull("item is in cache", item);

        return cache;
    }

    private void clearCache(AuthenticationContext context) {
        if (context.getCache() != null) {
            context.getCache().removeAll();
        }
    }

    class MockCache implements ITokenCacheStore {
        /**
         * serial version related to serializable interface
         */
        private static final long serialVersionUID = -3292746098551178627L;

        private static final String TAG = "MockCache";

        SparseArray<TokenCacheItem> mCache = new SparseArray<TokenCacheItem>();

        @Override
        public TokenCacheItem getItem(String key) {
            Log.d(TAG, "Mock cache get item:" + key.toString());
            return mCache.get(key.hashCode());
        }

        @Override
        public void setItem(String key, TokenCacheItem item) {
            Log.d(TAG, "Mock cache set item:" + item.toString());
            mCache.append(CacheKey.createCacheKey(item).hashCode(), item);
        }

        @Override
        public void removeItem(String key) {
            // TODO Auto-generated method stub
        }

        @Override
        public boolean contains(String key) {
            // TODO Auto-generated method stub
            return false;
        }

        @Override
        public void removeAll() {
            // TODO Auto-generated method stub
        }
    }

    class MockDiscovery implements IDiscovery {

        private boolean isValid = false;

        private URL authorizationUrl;

        MockDiscovery(boolean validFlag) {
            isValid = validFlag;
        }

        @Override
        public void isValidAuthority(URL authorizationEndpoint,
                AuthenticationCallback<Boolean> callback) {
            authorizationUrl = authorizationEndpoint;
            callback.onSuccess(isValid);
        }

        public URL getAuthorizationUrl() {
            return authorizationUrl;
        }
    }

    /**
     * Mock activity
     */
    class MockActivity extends Activity {

        private static final String TAG = "MockActivity";

        int mStartActivityRequestCode = -123;

        Intent mStartActivityIntent;

        CountDownLatch mSignal;

        Bundle mStartActivityOptions;

        public MockActivity(CountDownLatch signal) {
            mSignal = signal;
        }

        public MockActivity() {
            // TODO Auto-generated constructor stub
        }

        @Override
        public String getPackageName() {
            return ReflectionUtils.TEST_PACKAGE_NAME;
        }

        @Override
        public void startActivityForResult(Intent intent, int requestCode) {
            Log.d(TAG, "startActivityForResult:" + requestCode);
            mStartActivityIntent = intent;
            mStartActivityRequestCode = requestCode;
            // test call needs to stop the tests at this point. If it reaches
            // here, it means authenticationActivity was attempted to launch.
            // Since it is mock activity, it will not launch something.
            if (mSignal != null)
                mSignal.countDown();
        }

        @Override
        public void startActivityForResult(Intent intent, int requestCode, Bundle options) {
            Log.d(TAG, "startActivityForResult:" + requestCode);
            mStartActivityIntent = intent;
            mStartActivityRequestCode = requestCode;
            mStartActivityOptions = options;
            // test call needs to stop the tests at this point. If it reaches
            // here, it means authenticationActivity was attempted to launch.
            // Since it is mock activity, it will not launch something.
            if (mSignal != null)
                mSignal.countDown();
        }

    }
}
