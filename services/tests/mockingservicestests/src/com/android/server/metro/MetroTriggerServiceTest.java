/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.metro;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.metro.IMetroSession;
import android.metro.MetroContract;
import android.metro.MetroTriggerConfig;
import android.os.HandlerThread;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.location.LocationManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.modules.utils.testing.ExtendedMockitoRule;
import com.android.server.TelephonyRegistry;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.TimeZone;

@RunWith(AndroidJUnit4.class)
public class MetroTriggerServiceTest {
    private static final int OLD_USER = 10;
    private static final int NEW_USER = 11;

    @Rule
    public final ExtendedMockitoRule mExtendedMockitoRule = new ExtendedMockitoRule.Builder(this)
            .mockStatic(ServiceManager.class)
            .mockStatic(SystemProperties.class)
            .mockStatic(ActivityManager.class)
            .mockStatic(Settings.Secure.class)
            .mockStatic(Settings.Global.class)
            .build();

    private MetroTriggerService mService;

    @After
    public void tearDown() throws Exception {
        if (mService != null) {
            // quit() drops queued messages and join() waits for the one in flight, all while the
            // rule still owns the static mocks (@After runs before the rule's after()). Without
            // the join, a gating recheck could still run after the mocks were removed, which is
            // what turned a settings read in the worker thread into a process crash.
            final HandlerThread worker = (HandlerThread) getField("mWorkerThread");
            worker.quit();
            worker.join(1000);
        }
    }

    @Test
    public void userSwitchWhileBothUsersEligibleClosesOldSession() throws Exception {
        final Locale oldLocale = Locale.getDefault();
        final TimeZone oldZone = TimeZone.getDefault();
        try {
            Locale.setDefault(Locale.CHINA);
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));

            final Context context = mock(Context.class);
            final ContentResolver resolver = mock(ContentResolver.class);
            final UserManager users = mock(UserManager.class);
            final TelephonyManager telephony = mock(TelephonyManager.class);
            final LocationManager location = mock(LocationManager.class);
            when(context.getContentResolver()).thenReturn(resolver);
            when(context.getSystemService(UserManager.class)).thenReturn(users);
            when(context.getSystemService(TelephonyManager.class)).thenReturn(telephony);
            when(context.getSystemService(LocationManager.class)).thenReturn(location);
            when(location.isLocationEnabledForUser(UserHandle.of(NEW_USER))).thenReturn(true);
            when(users.isUserUnlocked(anyInt())).thenReturn(true);
            when(telephony.getActiveModemCount()).thenReturn(1);
            when(telephony.getSimState(0)).thenReturn(TelephonyManager.SIM_STATE_READY);
            doReturn(true).when(() -> SystemProperties.getBoolean(
                    eq(MetroContract.PROP_SUPPORTED), eq(false)));
            doReturn(NEW_USER).when(ActivityManager::getCurrentUser);
            doReturn(MetroContract.ENABLED_ON).when(() -> Settings.Secure.getIntForUser(
                    eq(resolver), eq(MetroContract.SECURE_ASSISTANT_ENABLED), anyInt(), anyInt()));
            doReturn("CN").when(() -> Settings.Secure.getStringForUser(
                    eq(resolver), eq(MetroContract.SECURE_REGION_OVERRIDE), anyInt()));

            mService = new MetroTriggerService(context, mock(TelephonyRegistry.class),
                    new File("/does/not/exist"));
            final MetroPackIndex pack = mock(MetroPackIndex.class);
            when(pack.packVersion()).thenReturn(2);
            when(pack.matchMode()).thenReturn(MetroContract.MATCH_MODE_LOCAL_CID_COMPAT);
            final MetroTriggerConfig oldConfig = new MetroTriggerConfig(OLD_USER, true, true,
                    MetroContract.CITY, 2, MetroContract.MATCH_MODE_LOCAL_CID_COMPAT, 1L);
            final IMetroSession oldSession = mock(IMetroSession.class);
            setField("mSystemRunning", true);
            setField("mPack", pack);
            setField("mConfig", oldConfig);
            setField("mObserving", true);
            setField("mBound", true);
            final Class<?> connectionClass = Class.forName(
                    "com.android.server.metro.MetroTriggerService$AppConnection");
            final Constructor<?> connectionConstructor = connectionClass.getDeclaredConstructor(
                    MetroTriggerService.class);
            connectionConstructor.setAccessible(true);
            setField("mAppConnection", connectionConstructor.newInstance(mService));
            setField("mSession", oldSession);
            setField("mAppUserId", OLD_USER);
            setField("mActiveCandidateId", 42L);
            setField("mLastStationCode", "old_station");

            invoke("recheckGating");

            verify(oldSession).onGatingChanged(false, oldConfig);
            verify(context).unbindService(any(ServiceConnection.class));
            assertThat((boolean) getField("mBound")).isFalse();
            assertThat((long) getField("mActiveCandidateId")).isEqualTo(0L);
            assertThat((String) getField("mLastStationCode")).isNull();
            assertThat(((MetroTriggerConfig) getField("mConfig")).getUserId())
                    .isEqualTo(NEW_USER);
            assertThat((boolean) getField("mObserving")).isTrue();
        } finally {
            Locale.setDefault(oldLocale);
            TimeZone.setDefault(oldZone);
        }
    }

    @Test
    public void locationModeBroadcastSchedulesGatingRecheck() throws Exception {
        final Context context = mock(Context.class);
        final ContentResolver resolver = mock(ContentResolver.class);
        when(context.getContentResolver()).thenReturn(resolver);
        when(context.getSystemService(UserManager.class)).thenReturn(mock(UserManager.class));
        when(context.getSystemService(TelephonyManager.class)).thenReturn(mock(TelephonyManager.class));
        doReturn(NEW_USER).when(ActivityManager::getCurrentUser);
        doReturn(MetroContract.ENABLED_ON).when(() -> Settings.Secure.getIntForUser(
                eq(resolver), eq(MetroContract.SECURE_ASSISTANT_ENABLED), anyInt(), anyInt()));
        doReturn("CN").when(() -> Settings.Secure.getStringForUser(
                eq(resolver), eq(MetroContract.SECURE_REGION_OVERRIDE), anyInt()));
        mService = new MetroTriggerService(context, mock(TelephonyRegistry.class),
                new File("/does/not/exist"));
        final ArgumentCaptor<BroadcastReceiver> receiver =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        final ArgumentCaptor<IntentFilter> filter = ArgumentCaptor.forClass(IntentFilter.class);
        verify(context).registerReceiverAsUser(receiver.capture(), eq(UserHandle.ALL),
                filter.capture(), eq(null), any(), eq(Context.RECEIVER_NOT_EXPORTED));
        assertThat(filter.getValue().hasAction(LocationManager.MODE_CHANGED_ACTION)).isTrue();

        receiver.getValue().onReceive(context, new Intent(LocationManager.MODE_CHANGED_ACTION));
        final long deadline = SystemClock.uptimeMillis() + 2000;
        while (!"waiting for boot completion".equals(getField("mGateDetail"))
                && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(10);
        }
        assertThat((long) getField("mGateEvents")).isEqualTo(1L);
        assertThat((String) getField("mGateDetail")).isEqualTo("waiting for boot completion");
    }

    /**
     * The boot order contract system_server depends on: SystemServer constructs this service
     * (startOtherServices:1664) before it starts ContentService (:1670), so any use of the
     * content resolver here dereferences a null IContentService and kills the system process.
     * The settings observers therefore have to wait for systemRunning(), which SystemServer
     * calls at :3629, once the content service is published.
     */
    @Test
    public void constructorMustNotTouchContentService() throws Exception {
        final Context context = mock(Context.class);
        final ContentResolver resolver = mock(ContentResolver.class);
        when(context.getContentResolver()).thenReturn(resolver);

        mService = new MetroTriggerService(context, mock(TelephonyRegistry.class),
                new File("/does/not/exist"));

        verify(resolver, never()).registerContentObserver(any(), anyBoolean(), any(), anyInt());

        mService.systemRunning();

        // Exactly one observer per gated settings key.
        verify(resolver, times(2)).registerContentObserver(any(), anyBoolean(), any(), anyInt());
    }

    private Object getField(String name) throws Exception {
        final Field field = MetroTriggerService.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(mService);
    }

    private void setField(String name, Object value) throws Exception {
        final Field field = MetroTriggerService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(mService, value);
    }

    private void invoke(String name) throws Exception {
        final Method method = MetroTriggerService.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(mService);
    }
}
