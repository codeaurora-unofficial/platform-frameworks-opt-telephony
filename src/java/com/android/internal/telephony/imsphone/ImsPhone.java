/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.internal.telephony.imsphone;

import static android.telephony.ims.ImsManager.EXTRA_WFC_REGISTRATION_FAILURE_MESSAGE;
import static android.telephony.ims.ImsManager.EXTRA_WFC_REGISTRATION_FAILURE_TITLE;

import static com.android.internal.telephony.CommandsInterface.CB_FACILITY_BAIC;
import static com.android.internal.telephony.CommandsInterface.CB_FACILITY_BAICr;
import static com.android.internal.telephony.CommandsInterface.CB_FACILITY_BAOC;
import static com.android.internal.telephony.CommandsInterface.CB_FACILITY_BAOIC;
import static com.android.internal.telephony.CommandsInterface.CB_FACILITY_BAOICxH;
import static com.android.internal.telephony.CommandsInterface.CB_FACILITY_BA_ALL;
import static com.android.internal.telephony.CommandsInterface.CB_FACILITY_BA_MO;
import static com.android.internal.telephony.CommandsInterface.CB_FACILITY_BA_MT;
import static com.android.internal.telephony.CommandsInterface.CB_FACILITY_BIC_ACR;
import static com.android.internal.telephony.CommandsInterface.CF_ACTION_DISABLE;
import static com.android.internal.telephony.CommandsInterface.CF_ACTION_ENABLE;
import static com.android.internal.telephony.CommandsInterface.CF_ACTION_ERASURE;
import static com.android.internal.telephony.CommandsInterface.CF_ACTION_REGISTRATION;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_ALL;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_ALL_CONDITIONAL;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_BUSY;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_NOT_REACHABLE;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_NO_REPLY;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_UNCONDITIONAL;
import static com.android.internal.telephony.CommandsInterface.SERVICE_CLASS_DATA_SYNC;
import static com.android.internal.telephony.CommandsInterface.SERVICE_CLASS_NONE;
import static com.android.internal.telephony.CommandsInterface.SERVICE_CLASS_PACKET;
import static com.android.internal.telephony.CommandsInterface.SERVICE_CLASS_VOICE;

import android.annotation.NonNull;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.ResultReceiver;
import android.os.UserHandle;
import android.telephony.AccessNetworkConstants;
import android.telephony.CarrierConfigManager;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.PhoneNumberUtils;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.UssdResponse;
import android.telephony.ims.ImsCallForwardInfo;
import android.telephony.ims.ImsCallProfile;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.ImsSsData;
import android.telephony.ims.ImsStreamMediaProfile;
import android.telephony.ims.ImsSsInfo;
import android.telephony.ims.RegistrationManager;
import android.telephony.ims.stub.ImsUtImplBase;
import android.text.TextUtils;
import android.util.LocalLog;

import com.android.ims.ImsCall;
import com.android.ims.ImsEcbm;
import com.android.ims.ImsEcbmStateListener;
import com.android.ims.ImsException;
import com.android.ims.ImsManager;
import com.android.ims.ImsUtInterface;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallFailCause;
import com.android.internal.telephony.CallForwardInfo;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.CallTracker;
import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.EcbmHandler;
import com.android.internal.telephony.GsmCdmaPhone;
import com.android.internal.telephony.MmiCode;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneNotifier;
import com.android.internal.telephony.ServiceStateTracker;
import com.android.internal.telephony.TelephonyComponentFactory;
import com.android.internal.telephony.dataconnection.TransportManager;
import com.android.internal.telephony.emergency.EmergencyNumberTracker;
import com.android.internal.telephony.gsm.GsmMmiCode;
import com.android.internal.telephony.gsm.SuppServiceNotification;
import com.android.internal.telephony.metrics.TelephonyMetrics;
import com.android.internal.telephony.metrics.VoiceCallSessionStats;
import com.android.internal.telephony.nano.TelephonyProto.ImsConnectionState;
import com.android.internal.telephony.uicc.IccRecords;
import com.android.internal.telephony.util.NotificationChannelController;
import com.android.internal.telephony.util.QtiImsUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.telephony.Rlog;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * {@hide}
 */
public class ImsPhone extends ImsPhoneBase {
    private static final String LOG_TAG = "ImsPhone";
    private static final boolean DBG = true;
    private static final boolean VDBG = false; // STOPSHIP if true

    private static final int EVENT_SET_CALL_BARRING_DONE             = EVENT_LAST + 1;
    private static final int EVENT_GET_CALL_BARRING_DONE             = EVENT_LAST + 2;
    private static final int EVENT_SET_CALL_WAITING_DONE             = EVENT_LAST + 3;
    private static final int EVENT_GET_CALL_WAITING_DONE             = EVENT_LAST + 4;
    private static final int EVENT_SET_CLIR_DONE                     = EVENT_LAST + 5;
    private static final int EVENT_GET_CLIR_DONE                     = EVENT_LAST + 6;
    private static final int EVENT_DEFAULT_PHONE_DATA_STATE_CHANGED  = EVENT_LAST + 7;
    @VisibleForTesting
    public static final int EVENT_SERVICE_STATE_CHANGED             = EVENT_LAST + 8;
    private static final int EVENT_VOICE_CALL_ENDED                  = EVENT_LAST + 9;
    private static final int EVENT_INITIATE_VOLTE_SILENT_REDIAL      = EVENT_LAST + 10;

    public static class ImsDialArgs extends DialArgs {
        public static class Builder extends DialArgs.Builder<ImsDialArgs.Builder> {
            private android.telecom.Connection.RttTextStream mRttTextStream;
            private int mClirMode = CommandsInterface.CLIR_DEFAULT;
            private int mRetryCallFailCause = ImsReasonInfo.CODE_UNSPECIFIED;
            private int mRetryCallFailNetworkType = TelephonyManager.NETWORK_TYPE_UNKNOWN;

            public static ImsDialArgs.Builder from(DialArgs dialArgs) {
                return new ImsDialArgs.Builder()
                        .setUusInfo(dialArgs.uusInfo)
                        .setVideoState(dialArgs.videoState)
                        .setIntentExtras(dialArgs.intentExtras);
            }

            public static ImsDialArgs.Builder from(ImsDialArgs dialArgs) {
                return new ImsDialArgs.Builder()
                        .setUusInfo(dialArgs.uusInfo)
                        .setVideoState(dialArgs.videoState)
                        .setIntentExtras(dialArgs.intentExtras)
                        .setRttTextStream(dialArgs.rttTextStream)
                        .setClirMode(dialArgs.clirMode)
                        .setRetryCallFailCause(dialArgs.retryCallFailCause)
                        .setRetryCallFailNetworkType(dialArgs.retryCallFailNetworkType);
            }

            public ImsDialArgs.Builder setRttTextStream(
                    android.telecom.Connection.RttTextStream s) {
                mRttTextStream = s;
                return this;
            }

            public ImsDialArgs.Builder setClirMode(int clirMode) {
                this.mClirMode = clirMode;
                return this;
            }

            public ImsDialArgs.Builder setRetryCallFailCause(int retryCallFailCause) {
                this.mRetryCallFailCause = retryCallFailCause;
                return this;
            }

            public ImsDialArgs.Builder setRetryCallFailNetworkType(int retryCallFailNetworkType) {
                this.mRetryCallFailNetworkType = retryCallFailNetworkType;
                return this;
            }

            public ImsDialArgs build() {
                return new ImsDialArgs(this);
            }
        }

        /**
         * The RTT text stream. If non-null, indicates that connection supports RTT
         * communication with the in-call app.
         */
        public final android.telecom.Connection.RttTextStream rttTextStream;

        /** The CLIR mode to use */
        public final int clirMode;
        public final int retryCallFailCause;
        public final int retryCallFailNetworkType;

        private ImsDialArgs(ImsDialArgs.Builder b) {
            super(b);
            this.rttTextStream = b.mRttTextStream;
            this.clirMode = b.mClirMode;
            this.retryCallFailCause = b.mRetryCallFailCause;
            this.retryCallFailNetworkType = b.mRetryCallFailNetworkType;
        }
    }

    // Instance Variables
    Phone mDefaultPhone;
    @UnsupportedAppUsage
    ImsPhoneCallTracker mCT;
    ImsExternalCallTracker mExternalCallTracker;
    @UnsupportedAppUsage
    private ArrayList <ImsPhoneMmiCode> mPendingMMIs = new ArrayList<ImsPhoneMmiCode>();
    @UnsupportedAppUsage
    private ServiceState mSS = new ServiceState();

    // To redial silently through GSM or CDMA when dialing through IMS fails
    private String mLastDialString;

    private WakeLock mWakeLock;

    private final RegistrantList mSilentRedialRegistrants = new RegistrantList();

    private final LocalLog mRegLocalLog = new LocalLog(100);
    private TelephonyMetrics mMetrics;

    // The helper class to receive and store the MmTel registration status updated.
    private ImsRegistrationCallbackHelper mImsMmTelRegistrationHelper;

    private boolean mRoaming = false;

    private boolean mIsInImsEcm = false;

    // List of Registrants to send supplementary service notifications to.
    private RegistrantList mSsnRegistrants = new RegistrantList();

    private Uri[] mCurrentSubscriberUris;

    protected void setCurrentSubscriberUris(Uri[] currentSubscriberUris) {
        this.mCurrentSubscriberUris = currentSubscriberUris;
    }

    @Override
    public Uri[] getCurrentSubscriberUris() {
        return mCurrentSubscriberUris;
    }

    @Override
    public int getEmergencyNumberDbVersion() {
        return getEmergencyNumberTracker().getEmergencyNumberDbVersion();
    }

    @Override
    public EmergencyNumberTracker getEmergencyNumberTracker() {
        return mDefaultPhone.getEmergencyNumberTracker();
    }

    @Override
    public ServiceStateTracker getServiceStateTracker() {
        return mDefaultPhone.getServiceStateTracker();
    }

    // Create Cf (Call forward) so that dialling number &
    // mIsCfu (true if reason is call forward unconditional)
    // mOnComplete (Message object passed by client) can be packed &
    // given as a single Cf object as user data to UtInterface.
    private static class Cf {
        final String mSetCfNumber;
        final Message mOnComplete;
        final boolean mIsCfu;
        final int mServiceClass;

        @UnsupportedAppUsage
        Cf(String cfNumber, boolean isCfu, Message onComplete, int serviceClass) {
            mSetCfNumber = cfNumber;
            mIsCfu = isCfu;
            mOnComplete = onComplete;
            mServiceClass = serviceClass;
        }
    }

    // Constructors
    public ImsPhone(Context context, PhoneNotifier notifier, Phone defaultPhone) {
        this(context, notifier, defaultPhone, false);
    }

    @VisibleForTesting
    public ImsPhone(Context context, PhoneNotifier notifier, Phone defaultPhone,
                    boolean unitTestMode) {
        super("ImsPhone", context, notifier, unitTestMode);

        mDefaultPhone = defaultPhone;
        // The ImsExternalCallTracker needs to be defined before the ImsPhoneCallTracker, as the
        // ImsPhoneCallTracker uses a thread to spool up the ImsManager.  Part of this involves
        // setting the multiendpoint listener on the external call tracker.  So we need to ensure
        // the external call tracker is available first to avoid potential timing issues.
        mExternalCallTracker =
                TelephonyComponentFactory.getInstance()
                        .inject(ImsExternalCallTracker.class.getName())
                        .makeImsExternalCallTracker(this);
        mCT = TelephonyComponentFactory.getInstance().inject(ImsPhoneCallTracker.class.getName())
                .makeImsPhoneCallTracker(this);
        mCT.registerPhoneStateListener(mExternalCallTracker);
        mExternalCallTracker.setCallPuller(mCT);

        mSS.setStateOff();

        mPhoneId = mDefaultPhone.getPhoneId();

        mMetrics = TelephonyMetrics.getInstance();

        mImsMmTelRegistrationHelper = new ImsRegistrationCallbackHelper(mMmTelRegistrationUpdate,
                context.getMainExecutor());

        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOG_TAG);
        mWakeLock.setReferenceCounted(false);

        if (mDefaultPhone.getServiceStateTracker() != null
                && mDefaultPhone.getTransportManager() != null) {
            for (int transport : mDefaultPhone.getTransportManager().getAvailableTransports()) {
                mDefaultPhone.getServiceStateTracker()
                        .registerForDataRegStateOrRatChanged(transport, this,
                                EVENT_DEFAULT_PHONE_DATA_STATE_CHANGED, null);
            }
        }
        // Sets the Voice reg state to STATE_OUT_OF_SERVICE and also queries the data service
        // state. We don't ever need the voice reg state to be anything other than in or out of
        // service.
        setServiceState(ServiceState.STATE_OUT_OF_SERVICE);

        mDefaultPhone.registerForServiceStateChanged(this, EVENT_SERVICE_STATE_CHANGED, null);
        // Force initial roaming state update later, on EVENT_CARRIER_CONFIG_CHANGED.
        // Settings provider or CarrierConfig may not be loaded now.

        mDefaultPhone.registerForVolteSilentRedial(this, EVENT_INITIATE_VOLTE_SILENT_REDIAL, null);

        // Register receiver for sending RTT text message and
        // for receving RTT Operation
        // .i.e.Upgrade Initiate, Upgrade accept, Upgrade reject
        IntentFilter filter2 = new IntentFilter();
        filter2.addAction(QtiImsUtils.ACTION_SEND_RTT_TEXT);
        filter2.addAction(QtiImsUtils.ACTION_RTT_OPERATION);
        mDefaultPhone.getContext().registerReceiver(mRttReceiver, filter2);
    }

    //todo: get rid of this function. It is not needed since parentPhone obj never changes
    @Override
    public void dispose() {
        logd("dispose");
        // Nothing to dispose in Phone
        //super.dispose();
        mPendingMMIs.clear();
        mExternalCallTracker.tearDown();
        mCT.unregisterPhoneStateListener(mExternalCallTracker);
        mCT.unregisterForVoiceCallEnded(this);
        mCT.dispose();

        //Force all referenced classes to unregister their former registered events
        if (mDefaultPhone != null && mDefaultPhone.getServiceStateTracker() != null) {
            for (int transport : mDefaultPhone.getTransportManager().getAvailableTransports()) {
                mDefaultPhone.getServiceStateTracker()
                        .unregisterForDataRegStateOrRatChanged(transport, this);
            }
            mDefaultPhone.unregisterForServiceStateChanged(this);
            mDefaultPhone.getContext().unregisterReceiver(mRttReceiver);
        }

        if (mDefaultPhone != null) {
            mDefaultPhone.unregisterForVolteSilentRedial(this);
        }
    }

    @UnsupportedAppUsage
    @Override
    public ServiceState getServiceState() {
        return mSS;
    }

    @UnsupportedAppUsage
    @VisibleForTesting
    public void setServiceState(int state) {
        boolean isVoiceRegStateChanged = false;

        synchronized (this) {
            isVoiceRegStateChanged = mSS.getState() != state;
            mSS.setVoiceRegState(state);
        }
        updateDataServiceState();

        if (isVoiceRegStateChanged) {
            if (mDefaultPhone.getServiceStateTracker() != null) {
                mDefaultPhone.getServiceStateTracker().onImsServiceStateChanged();
            }
        }
    }

    @Override
    public CallTracker getCallTracker() {
        return mCT;
    }

    public void setVoiceCallForwardingFlag(int line, boolean enable, String number) {
        IccRecords r = mDefaultPhone.getIccRecords();
        if (r != null) {
            setVoiceCallForwardingFlag(r, line, enable, number);
        }
    }

    public ImsExternalCallTracker getExternalCallTracker() {
        return mExternalCallTracker;
    }

    @Override
    public List<? extends ImsPhoneMmiCode>
    getPendingMmiCodes() {
        return mPendingMMIs;
    }

    @Override
    public void
    acceptCall(int videoState) throws CallStateException {
        mCT.acceptCall(videoState);
    }

    @Override
    public void
    rejectCall() throws CallStateException {
        mCT.rejectCall();
    }

    @Override
    public void
    switchHoldingAndActive() throws CallStateException {
        throw new UnsupportedOperationException("Use hold() and unhold() instead.");
    }

    @Override
    public boolean canConference() {
        return mCT.canConference();
    }

    public boolean canDial() {
        try {
            mCT.checkForDialIssues();
        } catch (CallStateException cse) {
            return false;
        }
        return true;
    }

    @Override
    public void conference() {
        mCT.conference();
    }

    @Override
    public void clearDisconnected() {
        mCT.clearDisconnected();
    }

    @Override
    public boolean canTransfer() {
        return mCT.canTransfer();
    }

    @Override
    public void explicitCallTransfer() throws CallStateException {
        mCT.explicitCallTransfer();
    }

    @UnsupportedAppUsage
    @Override
    public ImsPhoneCall
    getForegroundCall() {
        return mCT.mForegroundCall;
    }

    @UnsupportedAppUsage
    @Override
    public ImsPhoneCall
    getBackgroundCall() {
        return mCT.mBackgroundCall;
    }

    @UnsupportedAppUsage
    @Override
    public ImsPhoneCall
    getRingingCall() {
        return mCT.mRingingCall;
    }

    @Override
    public boolean isImsAvailable() {
        return mCT.isImsServiceReady();
    }

    /**
     * Hold the currently active call, possibly unholding a currently held call.
     * @throws CallStateException
     */
    public void holdActiveCall() throws CallStateException {
        mCT.holdActiveCall();
    }

    /**
     * Unhold the currently active call, possibly holding a currently active call.
     * If the call tracker is already in the middle of a hold operation, this is a noop.
     * @throws CallStateException
     */
    public void unholdHeldCall() throws CallStateException {
        mCT.unholdHeldCall();
    }

    private boolean handleCallDeflectionIncallSupplementaryService(
            String dialString) {
        if (dialString.length() > 1) {
            return false;
        }

        if (getRingingCall().getState() != ImsPhoneCall.State.IDLE) {
            if (DBG) logd("MmiCode 0: rejectCall");
            try {
                mCT.rejectCall();
            } catch (CallStateException e) {
                if (DBG) Rlog.d(LOG_TAG, "reject failed", e);
                notifySuppServiceFailed(Phone.SuppService.REJECT);
            }
        } else if (getBackgroundCall().getState() != ImsPhoneCall.State.IDLE) {
            if (DBG) logd("MmiCode 0: hangupWaitingOrBackground");
            try {
                mCT.hangup(getBackgroundCall());
            } catch (CallStateException e) {
                if (DBG) Rlog.d(LOG_TAG, "hangup failed", e);
            }
        }

        return true;
    }

    private void sendUssdResponse(String ussdRequest, CharSequence message, int returnCode,
                                   ResultReceiver wrappedCallback) {
        UssdResponse response = new UssdResponse(ussdRequest, message);
        Bundle returnData = new Bundle();
        returnData.putParcelable(TelephonyManager.USSD_RESPONSE, response);
        wrappedCallback.send(returnCode, returnData);

    }

    @Override
    public boolean handleUssdRequest(String ussdRequest, ResultReceiver wrappedCallback)
            throws CallStateException {
        if (mPendingMMIs.size() > 0) {
            // There are MMI codes in progress; fail attempt now.
            logi("handleUssdRequest: queue full: " + Rlog.pii(LOG_TAG, ussdRequest));
            sendUssdResponse(ussdRequest, null, TelephonyManager.USSD_RETURN_FAILURE,
                    wrappedCallback );
            return true;
        }
        try {
            dialInternal(ussdRequest, new ImsDialArgs.Builder().build(), wrappedCallback);
        } catch (CallStateException cse) {
            if (CS_FALLBACK.equals(cse.getMessage())) {
                throw cse;
            } else {
                Rlog.w(LOG_TAG, "Could not execute USSD " + cse);
                sendUssdResponse(ussdRequest, null, TelephonyManager.USSD_RETURN_FAILURE,
                        wrappedCallback);
            }
        } catch (Exception e) {
            Rlog.w(LOG_TAG, "Could not execute USSD " + e);
            sendUssdResponse(ussdRequest, null, TelephonyManager.USSD_RETURN_FAILURE,
                    wrappedCallback);
            return false;
        }
        return true;
    }

    private boolean handleCallWaitingIncallSupplementaryService(
            String dialString) {
        int len = dialString.length();

        if (len > 2) {
            return false;
        }

        ImsPhoneCall call = getForegroundCall();

        try {
            if (len > 1) {
                if (DBG) logd("not support 1X SEND");
                notifySuppServiceFailed(Phone.SuppService.HANGUP);
            } else {
                if (call.getState() != ImsPhoneCall.State.IDLE) {
                    if (DBG) logd("MmiCode 1: hangup foreground");
                    mCT.hangup(call);
                } else {
                    if (DBG) logd("MmiCode 1: holdActiveCallForWaitingCall");
                    mCT.holdActiveCallForWaitingCall();
                }
            }
        } catch (CallStateException e) {
            if (DBG) Rlog.d(LOG_TAG, "hangup failed", e);
            notifySuppServiceFailed(Phone.SuppService.HANGUP);
        }

        return true;
    }

    private boolean handleCallHoldIncallSupplementaryService(String dialString) {
        int len = dialString.length();

        if (len > 2) {
            return false;
        }

        if (len > 1) {
            if (DBG) logd("separate not supported");
            notifySuppServiceFailed(Phone.SuppService.SEPARATE);
        } else {
            try {
                if (getRingingCall().getState() != ImsPhoneCall.State.IDLE) {
                    if (DBG) logd("MmiCode 2: accept ringing call");
                    mCT.acceptCall(ImsCallProfile.CALL_TYPE_VOICE);
                } else if (getBackgroundCall().getState() == ImsPhoneCall.State.HOLDING) {
                    // If there's an active ongoing call as well, hold it and the background one
                    // should automatically unhold. Otherwise just unhold the background call.
                    if (getForegroundCall().getState() != ImsPhoneCall.State.IDLE) {
                        if (DBG) logd("MmiCode 2: switch holding and active");
                        mCT.holdActiveCall();
                    } else {
                        if (DBG) logd("MmiCode 2: unhold held call");
                        mCT.unholdHeldCall();
                    }
                } else if (getForegroundCall().getState() != ImsPhoneCall.State.IDLE) {
                    if (DBG) logd("MmiCode 2: hold active call");
                    mCT.holdActiveCall();
                }
            } catch (CallStateException e) {
                if (DBG) Rlog.d(LOG_TAG, "switch failed", e);
                notifySuppServiceFailed(Phone.SuppService.SWITCH);
            }
        }

        return true;
    }

    private boolean handleMultipartyIncallSupplementaryService(
            String dialString) {
        if (dialString.length() > 1) {
            return false;
        }

        if (DBG) logd("MmiCode 3: merge calls");
        conference();
        return true;
    }

    private boolean handleEctIncallSupplementaryService(String dialString) {
        if (dialString.length() != 1) {
            return false;
        }

        if (DBG) logd("MmiCode 4: explicit call transfer");
        try {
            explicitCallTransfer();
        } catch (CallStateException e) {
            if (DBG) Rlog.d(LOG_TAG, "explicit call transfer failed", e);
            notifySuppServiceFailed(Phone.SuppService.TRANSFER);
        }
        return true;
    }

    private boolean handleCcbsIncallSupplementaryService(String dialString) {
        if (dialString.length() > 1) {
            return false;
        }

        logi("MmiCode 5: CCBS not supported!");
        // Treat it as an "unknown" service.
        notifySuppServiceFailed(Phone.SuppService.UNKNOWN);
        return true;
    }

    public void notifySuppSvcNotification(SuppServiceNotification suppSvc) {
        logd("notifySuppSvcNotification: suppSvc = " + suppSvc);

        AsyncResult ar = new AsyncResult(null, suppSvc, null);
        mSsnRegistrants.notifyRegistrants(ar);
    }

    @UnsupportedAppUsage
    @Override
    public boolean handleInCallMmiCommands(String dialString) {
        if (!isInCall()) {
            return false;
        }

        if (TextUtils.isEmpty(dialString)) {
            return false;
        }

        boolean result = false;
        char ch = dialString.charAt(0);
        switch (ch) {
            case '0':
                result = handleCallDeflectionIncallSupplementaryService(
                        dialString);
                break;
            case '1':
                result = handleCallWaitingIncallSupplementaryService(
                        dialString);
                break;
            case '2':
                result = handleCallHoldIncallSupplementaryService(dialString);
                break;
            case '3':
                result = handleMultipartyIncallSupplementaryService(dialString);
                break;
            case '4':
                result = handleEctIncallSupplementaryService(dialString);
                break;
            case '5':
                result = handleCcbsIncallSupplementaryService(dialString);
                break;
            default:
                break;
        }

        return result;
    }

    boolean isInCall() {
        ImsPhoneCall.State foregroundCallState = getForegroundCall().getState();
        ImsPhoneCall.State backgroundCallState = getBackgroundCall().getState();
        ImsPhoneCall.State ringingCallState = getRingingCall().getState();

       return (foregroundCallState.isAlive() ||
               backgroundCallState.isAlive() ||
               ringingCallState.isAlive());
    }

    @Override
    public boolean isInImsEcm() {
        return mIsInImsEcm;
    }

    public void notifyNewRingingConnection(Connection c) {
        mDefaultPhone.notifyNewRingingConnectionP(c);
    }

    @UnsupportedAppUsage
    void notifyUnknownConnection(Connection c) {
        mDefaultPhone.notifyUnknownConnectionP(c);
    }

    @Override
    public void notifyForVideoCapabilityChanged(boolean isVideoCapable) {
        mIsVideoCapable = isVideoCapable;
        mDefaultPhone.notifyForVideoCapabilityChanged(isVideoCapable);
    }

    @Override
    public void setRadioPower(boolean on, boolean forEmergencyCall,
            boolean isSelectedPhoneForEmergencyCall, boolean forceApply) {
        mDefaultPhone.setRadioPower(on, forEmergencyCall, isSelectedPhoneForEmergencyCall,
                forceApply);
    }

    @Override
    public Connection startConference(String[] participantsToDial, DialArgs dialArgs)
            throws CallStateException {
         ImsDialArgs.Builder imsDialArgsBuilder;
         if (!(dialArgs instanceof ImsDialArgs)) {
             imsDialArgsBuilder = ImsDialArgs.Builder.from(dialArgs);
         } else {
             imsDialArgsBuilder = ImsDialArgs.Builder.from((ImsDialArgs) dialArgs);
         }
         return mCT.startConference(participantsToDial, imsDialArgsBuilder.build());
    }

    @Override
    public Connection dial(String dialString, DialArgs dialArgs) throws CallStateException {
        return dialInternal(dialString, dialArgs, null);
    }

    private Connection dialInternal(String dialString, DialArgs dialArgs,
                                    ResultReceiver wrappedCallback)
            throws CallStateException {

        mLastDialString = dialString;

        String newDialString = dialString;
        // Need to make sure dialString gets parsed properly.
        newDialString = PhoneNumberUtils.stripSeparators(dialString);

        // handle in-call MMI first if applicable
        if (handleInCallMmiCommands(newDialString)) {
            return null;
        }

        ImsDialArgs.Builder imsDialArgsBuilder;
        // Get the CLIR info if needed
        if (!(dialArgs instanceof ImsDialArgs)) {
            imsDialArgsBuilder = ImsDialArgs.Builder.from(dialArgs);
        } else {
            imsDialArgsBuilder = ImsDialArgs.Builder.from((ImsDialArgs) dialArgs);
        }
        imsDialArgsBuilder.setClirMode(mCT.getClirMode());

        if (mDefaultPhone.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA) {
            return mCT.dial(dialString, imsDialArgsBuilder.build());
        }

        // Only look at the Network portion for mmi
        String networkPortion = PhoneNumberUtils.extractNetworkPortionAlt(newDialString);
        ImsPhoneMmiCode mmi =
                ImsPhoneMmiCode.newFromDialString(networkPortion, this, wrappedCallback);
        if (DBG) logd("dialInternal: dialing w/ mmi '" + mmi + "'...");

        if (mmi == null) {
            return mCT.dial(dialString, imsDialArgsBuilder.build());
        } else if (mmi.isTemporaryModeCLIR()) {
            imsDialArgsBuilder.setClirMode(mmi.getCLIRMode());
            return mCT.dial(mmi.getDialingNumber(), imsDialArgsBuilder.build());
        } else if (!mmi.isSupportedOverImsPhone()) {
            // If the mmi is not supported by IMS service,
            // try to initiate dialing with default phone
            // Note: This code is never reached; there is a bug in isSupportedOverImsPhone which
            // causes it to return true even though the "processCode" method ultimately throws the
            // exception.
            logi("dialInternal: USSD not supported by IMS; fallback to CS.");
            throw new CallStateException(CS_FALLBACK);
        } else {
            mPendingMMIs.add(mmi);
            mMmiRegistrants.notifyRegistrants(new AsyncResult(null, mmi, null));

            try {
                mmi.processCode();
            } catch (CallStateException cse) {
                if (CS_FALLBACK.equals(cse.getMessage())) {
                    logi("dialInternal: fallback to GSM required.");
                    // Make sure we remove from the list of pending MMIs since it will handover to
                    // GSM.
                    mPendingMMIs.remove(mmi);
                    throw cse;
                }
            }

            return null;
        }
    }

    @Override
    public void
    sendDtmf(char c) {
        if (!PhoneNumberUtils.is12Key(c)) {
            loge("sendDtmf called with invalid character '" + c + "'");
        } else {
            if (mCT.getState() ==  PhoneConstants.State.OFFHOOK) {
                mCT.sendDtmf(c, null);
            }
        }
    }

    @Override
    public void
    startDtmf(char c) {
        if (!(PhoneNumberUtils.is12Key(c) || (c >= 'A' && c <= 'D'))) {
            loge("startDtmf called with invalid character '" + c + "'");
        } else {
            mCT.startDtmf(c);
        }
    }

    @Override
    public void
    stopDtmf() {
        mCT.stopDtmf();
    }

    public void notifyIncomingRing() {
        if (DBG) logd("notifyIncomingRing");
        AsyncResult ar = new AsyncResult(null, null, null);
        sendMessage(obtainMessage(EVENT_CALL_RING, ar));
    }

    @Override
    public void setMute(boolean muted) {
        mCT.setMute(muted);
    }

    @Override
    public void setTTYMode(int ttyMode, Message onComplete) {
        mCT.setTtyMode(ttyMode);
    }

    @Override
    public void setUiTTYMode(int uiTtyMode, Message onComplete) {
        mCT.setUiTTYMode(uiTtyMode, onComplete);
    }

    @Override
    public boolean getMute() {
        return mCT.getMute();
    }

    @UnsupportedAppUsage
    @Override
    public PhoneConstants.State getState() {
        return mCT.getState();
    }

    @UnsupportedAppUsage
    private boolean isValidCommandInterfaceCFReason (int commandInterfaceCFReason) {
        switch (commandInterfaceCFReason) {
        case CF_REASON_UNCONDITIONAL:
        case CF_REASON_BUSY:
        case CF_REASON_NO_REPLY:
        case CF_REASON_NOT_REACHABLE:
        case CF_REASON_ALL:
        case CF_REASON_ALL_CONDITIONAL:
            return true;
        default:
            return false;
        }
    }

    @UnsupportedAppUsage
    private boolean isValidCommandInterfaceCFAction (int commandInterfaceCFAction) {
        switch (commandInterfaceCFAction) {
        case CF_ACTION_DISABLE:
        case CF_ACTION_ENABLE:
        case CF_ACTION_REGISTRATION:
        case CF_ACTION_ERASURE:
            return true;
        default:
            return false;
        }
    }

    @UnsupportedAppUsage
    private  boolean isCfEnable(int action) {
        return (action == CF_ACTION_ENABLE) || (action == CF_ACTION_REGISTRATION);
    }

    @UnsupportedAppUsage
    private int getConditionFromCFReason(int reason) {
        switch(reason) {
            case CF_REASON_UNCONDITIONAL: return ImsUtInterface.CDIV_CF_UNCONDITIONAL;
            case CF_REASON_BUSY: return ImsUtInterface.CDIV_CF_BUSY;
            case CF_REASON_NO_REPLY: return ImsUtInterface.CDIV_CF_NO_REPLY;
            case CF_REASON_NOT_REACHABLE: return ImsUtInterface.CDIV_CF_NOT_REACHABLE;
            case CF_REASON_ALL: return ImsUtInterface.CDIV_CF_ALL;
            case CF_REASON_ALL_CONDITIONAL: return ImsUtInterface.CDIV_CF_ALL_CONDITIONAL;
            default:
                break;
        }

        return ImsUtInterface.INVALID;
    }

    private int getCFReasonFromCondition(int condition) {
        switch(condition) {
            case ImsUtInterface.CDIV_CF_UNCONDITIONAL: return CF_REASON_UNCONDITIONAL;
            case ImsUtInterface.CDIV_CF_BUSY: return CF_REASON_BUSY;
            case ImsUtInterface.CDIV_CF_NO_REPLY: return CF_REASON_NO_REPLY;
            case ImsUtInterface.CDIV_CF_NOT_REACHABLE: return CF_REASON_NOT_REACHABLE;
            case ImsUtInterface.CDIV_CF_ALL: return CF_REASON_ALL;
            case ImsUtInterface.CDIV_CF_ALL_CONDITIONAL: return CF_REASON_ALL_CONDITIONAL;
            default:
                break;
        }

        return CF_REASON_NOT_REACHABLE;
    }

    @UnsupportedAppUsage
    private int getActionFromCFAction(int action) {
        switch(action) {
            case CF_ACTION_DISABLE: return ImsUtInterface.ACTION_DEACTIVATION;
            case CF_ACTION_ENABLE: return ImsUtInterface.ACTION_ACTIVATION;
            case CF_ACTION_ERASURE: return ImsUtInterface.ACTION_ERASURE;
            case CF_ACTION_REGISTRATION: return ImsUtInterface.ACTION_REGISTRATION;
            default:
                break;
        }

        return ImsUtInterface.INVALID;
    }

    @Override
    public void getOutgoingCallerIdDisplay(Message onComplete) {
        if (DBG) logd("getCLIR");
        Message resp;
        resp = obtainMessage(EVENT_GET_CLIR_DONE, onComplete);

        try {
            ImsUtInterface ut = mCT.getUtInterface();
            ut.queryCLIR(resp);
        } catch (ImsException e) {
            sendErrorResponse(onComplete, e);
        }
    }

    @Override
    public void setOutgoingCallerIdDisplay(int clirMode, Message onComplete) {
        if (DBG) logd("setCLIR action= " + clirMode);
        Message resp;
        // Packing CLIR value in the message. This will be required for
        // SharedPreference caching, if the message comes back as part of
        // a success response.
        resp = obtainMessage(EVENT_SET_CLIR_DONE, clirMode, 0, onComplete);
        try {
            ImsUtInterface ut = mCT.getUtInterface();
            ut.updateCLIR(clirMode, resp);
        } catch (ImsException e) {
            sendErrorResponse(onComplete, e);
        }
    }

    @UnsupportedAppUsage
    @Override
    public void getCallForwardingOption(int commandInterfaceCFReason,
            Message onComplete) {
        getCallForwardingOption(commandInterfaceCFReason,
                SERVICE_CLASS_VOICE, onComplete);
    }

    @Override
    public void getCallForwardingOption(int commandInterfaceCFReason, int serviceClass,
            Message onComplete) {
        if (DBG) Rlog.d(LOG_TAG, "getCallForwardingOption reason=" + commandInterfaceCFReason +
                "serviceclass =" + serviceClass);
        if (isValidCommandInterfaceCFReason(commandInterfaceCFReason)) {
            if (DBG) Rlog.d(LOG_TAG, "requesting call forwarding query.");
            Message resp;
            resp = obtainMessage(EVENT_GET_CALL_FORWARD_DONE, onComplete);

            try {
                ImsUtInterface ut = mCT.getUtInterface();
                ut.queryCallForward(getConditionFromCFReason(commandInterfaceCFReason), null,
                        serviceClass, resp);
            } catch (ImsException e) {
                sendErrorResponse(onComplete, e);
            }
        } else if (onComplete != null) {
            sendErrorResponse(onComplete);
        }
    }

    @Override
    public void setCallForwardingOption(int commandInterfaceCFAction,
            int commandInterfaceCFReason,
            String dialingNumber,
            int timerSeconds,
            Message onComplete) {
        setCallForwardingOption(commandInterfaceCFAction, commandInterfaceCFReason, dialingNumber,
                CommandsInterface.SERVICE_CLASS_VOICE, timerSeconds, onComplete);
    }

    @UnsupportedAppUsage
    @Override
    public void setCallForwardingOption(int commandInterfaceCFAction,
            int commandInterfaceCFReason,
            String dialingNumber,
            int serviceClass,
            int timerSeconds,
            Message onComplete) {
        if (DBG) {
            logd("setCallForwardingOption action=" + commandInterfaceCFAction
                    + ", reason=" + commandInterfaceCFReason + " serviceClass=" + serviceClass);
        }
        if ((isValidCommandInterfaceCFAction(commandInterfaceCFAction)) &&
                (isValidCommandInterfaceCFReason(commandInterfaceCFReason))) {
            Message resp;
            Cf cf = new Cf(dialingNumber, GsmMmiCode.isVoiceUnconditionalForwarding(
                    commandInterfaceCFReason, serviceClass), onComplete, serviceClass);
            resp = obtainMessage(EVENT_SET_CALL_FORWARD_DONE,
                    isCfEnable(commandInterfaceCFAction) ? 1 : 0, 0, cf);

            try {
                ImsUtInterface ut = mCT.getUtInterface();
                ut.updateCallForward(getActionFromCFAction(commandInterfaceCFAction),
                        getConditionFromCFReason(commandInterfaceCFReason),
                        dialingNumber,
                        serviceClass,
                        timerSeconds,
                        resp);
            } catch (ImsException e) {
                sendErrorResponse(onComplete, e);
            }
        } else if (onComplete != null) {
            sendErrorResponse(onComplete);
        }
    }

    @UnsupportedAppUsage
    @Override
    public void getCallWaiting(Message onComplete) {
        if (DBG) logd("getCallWaiting");
        Message resp;
        resp = obtainMessage(EVENT_GET_CALL_WAITING_DONE, onComplete);

        try {
            ImsUtInterface ut = mCT.getUtInterface();
            ut.queryCallWaiting(resp);
        } catch (ImsException e) {
            sendErrorResponse(onComplete, e);
        }
    }

    @UnsupportedAppUsage
    @Override
    public void setCallWaiting(boolean enable, Message onComplete) {
        int serviceClass = CommandsInterface.SERVICE_CLASS_VOICE;
        CarrierConfigManager configManager = (CarrierConfigManager)
                getContext().getSystemService(Context.CARRIER_CONFIG_SERVICE);
        PersistableBundle b = configManager.getConfigForSubId(getSubId());
        if (b != null) {
            serviceClass = b.getInt(CarrierConfigManager.KEY_CALL_WAITING_SERVICE_CLASS_INT,
                    CommandsInterface.SERVICE_CLASS_VOICE);
        }
        setCallWaiting(enable, serviceClass, onComplete);
    }

    public void setCallWaiting(boolean enable, int serviceClass, Message onComplete) {
        if (DBG) logd("setCallWaiting enable=" + enable);
        Message resp;
        resp = obtainMessage(EVENT_SET_CALL_WAITING_DONE, onComplete);

        try {
            ImsUtInterface ut = mCT.getUtInterface();
            ut.updateCallWaiting(enable, serviceClass, resp);
        } catch (ImsException e) {
            sendErrorResponse(onComplete, e);
        }
    }

    private int getCBTypeFromFacility(String facility) {
        if (CB_FACILITY_BAOC.equals(facility)) {
            return ImsUtImplBase.CALL_BARRING_ALL_OUTGOING;
        } else if (CB_FACILITY_BAOIC.equals(facility)) {
            return ImsUtImplBase.CALL_BARRING_OUTGOING_INTL;
        } else if (CB_FACILITY_BAOICxH.equals(facility)) {
            return ImsUtImplBase.CALL_BARRING_OUTGOING_INTL_EXCL_HOME;
        } else if (CB_FACILITY_BAIC.equals(facility)) {
            return ImsUtImplBase.CALL_BARRING_ALL_INCOMING;
        } else if (CB_FACILITY_BAICr.equals(facility)) {
            return ImsUtImplBase.CALL_BLOCKING_INCOMING_WHEN_ROAMING;
        } else if (CB_FACILITY_BA_ALL.equals(facility)) {
            return ImsUtImplBase.CALL_BARRING_ALL;
        } else if (CB_FACILITY_BA_MO.equals(facility)) {
            return ImsUtImplBase.CALL_BARRING_OUTGOING_ALL_SERVICES;
        } else if (CB_FACILITY_BA_MT.equals(facility)) {
            return ImsUtImplBase.CALL_BARRING_INCOMING_ALL_SERVICES;
        } else if (CB_FACILITY_BIC_ACR.equals(facility)) {
            return ImsUtImplBase.CALL_BARRING_ANONYMOUS_INCOMING;
        }

        return 0;
    }

    public void getCallBarring(String facility, Message onComplete) {
        getCallBarring(facility, onComplete, CommandsInterface.SERVICE_CLASS_VOICE);
    }

    public void getCallBarring(String facility, Message onComplete, int serviceClass) {
        getCallBarring(facility, "", onComplete, serviceClass);
    }

    @Override
    public void getCallBarring(String facility, String password, Message onComplete,
            int serviceClass) {
        if (DBG) logd("getCallBarring facility=" + facility + ", serviceClass = " + serviceClass);
        Message resp;
        resp = obtainMessage(EVENT_GET_CALL_BARRING_DONE, onComplete);

        try {
            ImsUtInterface ut = mCT.getUtInterface();
            // password is not required with Ut interface
            ut.queryCallBarring(getCBTypeFromFacility(facility), resp, serviceClass);
        } catch (ImsException e) {
            sendErrorResponse(onComplete, e);
        }
    }

    public void setCallBarring(String facility, boolean lockState, String password,
            Message onComplete) {
        setCallBarring(facility, lockState, password, onComplete,
                CommandsInterface.SERVICE_CLASS_VOICE);
    }

    @Override
    public void setCallBarring(String facility, boolean lockState, String password,
            Message onComplete, int serviceClass) {
        if (DBG) {
            logd("setCallBarring facility=" + facility
                    + ", lockState=" + lockState + ", serviceClass = " + serviceClass);
        }
        Message resp;
        resp = obtainMessage(EVENT_SET_CALL_BARRING_DONE, onComplete);

        int action;
        if (lockState) {
            action = CommandsInterface.CF_ACTION_ENABLE;
        }
        else {
            action = CommandsInterface.CF_ACTION_DISABLE;
        }

        try {
            ImsUtInterface ut = mCT.getUtInterface();
            ut.updateCallBarring(getCBTypeFromFacility(facility), action,
                    resp, null, serviceClass, password);
        } catch (ImsException e) {
            sendErrorResponse(onComplete, e);
        }
    }

    @Override
    public void sendUssdResponse(String ussdMessge) {
        logd("sendUssdResponse");
        ImsPhoneMmiCode mmi = ImsPhoneMmiCode.newFromUssdUserInput(ussdMessge, this);
        mPendingMMIs.add(mmi);
        mMmiRegistrants.notifyRegistrants(new AsyncResult(null, mmi, null));
        mmi.sendUssd(ussdMessge);
    }

    public void sendUSSD(String ussdString, Message response) {
        Rlog.d(LOG_TAG, "sendUssd ussdString = " + ussdString);
        mLastDialString = ussdString;
        mCT.sendUSSD(ussdString, response);
    }

    @Override
    public void cancelUSSD(Message msg) {
        mCT.cancelUSSD(msg);
    }

    @UnsupportedAppUsage
    private void sendErrorResponse(Message onComplete) {
        logd("sendErrorResponse");
        if (onComplete != null) {
            AsyncResult.forMessage(onComplete, null,
                    new CommandException(CommandException.Error.GENERIC_FAILURE));
            onComplete.sendToTarget();
        }
    }

    @UnsupportedAppUsage
    @VisibleForTesting
    public void sendErrorResponse(Message onComplete, Throwable e) {
        logd("sendErrorResponse");
        if (onComplete != null) {
            AsyncResult.forMessage(onComplete, null, getCommandException(e));
            onComplete.sendToTarget();
        }
    }

    private CommandException getCommandException(int code, String errorString) {
        logd("getCommandException code= " + code + ", errorString= " + errorString);
        CommandException.Error error = CommandException.Error.GENERIC_FAILURE;

        switch(code) {
            case ImsReasonInfo.CODE_UT_NOT_SUPPORTED:
                error = CommandException.Error.REQUEST_NOT_SUPPORTED;
                break;
            case ImsReasonInfo.CODE_UT_CB_PASSWORD_MISMATCH:
                error = CommandException.Error.PASSWORD_INCORRECT;
                break;
            case ImsReasonInfo.CODE_UT_SERVICE_UNAVAILABLE:
                error = CommandException.Error.RADIO_NOT_AVAILABLE;
                break;
            case ImsReasonInfo.CODE_FDN_BLOCKED:
                error = CommandException.Error.FDN_CHECK_FAILURE;
                break;
            case ImsReasonInfo.CODE_UT_SS_MODIFIED_TO_DIAL:
                error = CommandException.Error.SS_MODIFIED_TO_DIAL;
                break;
            case ImsReasonInfo.CODE_UT_SS_MODIFIED_TO_USSD:
                error = CommandException.Error.SS_MODIFIED_TO_USSD;
                break;
            case ImsReasonInfo.CODE_UT_SS_MODIFIED_TO_SS:
                error = CommandException.Error.SS_MODIFIED_TO_SS;
                break;
            case ImsReasonInfo.CODE_UT_SS_MODIFIED_TO_DIAL_VIDEO:
                error = CommandException.Error.SS_MODIFIED_TO_DIAL_VIDEO;
                break;
            default:
                break;
        }

        return new CommandException(error, errorString);
    }

    private CommandException getCommandException(Throwable e) {
        CommandException ex = null;

        if (e instanceof ImsException) {
            ex = getCommandException(((ImsException)e).getCode(), e.getMessage());
        } else {
            logd("getCommandException generic failure");
            ex = new CommandException(CommandException.Error.GENERIC_FAILURE);
        }
        return ex;
    }

    private void
    onNetworkInitiatedUssd(ImsPhoneMmiCode mmi) {
        logd("onNetworkInitiatedUssd");
        mMmiCompleteRegistrants.notifyRegistrants(
            new AsyncResult(null, mmi, null));
    }

    /* package */
    void onIncomingUSSD(int ussdMode, String ussdMessage) {
        if (DBG) logd("onIncomingUSSD ussdMode=" + ussdMode);

        boolean isUssdError;
        boolean isUssdRequest;

        isUssdRequest
            = (ussdMode == CommandsInterface.USSD_MODE_REQUEST);

        isUssdError
            = (ussdMode != CommandsInterface.USSD_MODE_NOTIFY
                && ussdMode != CommandsInterface.USSD_MODE_REQUEST);

        ImsPhoneMmiCode found = null;
        for (int i = 0, s = mPendingMMIs.size() ; i < s; i++) {
            if(mPendingMMIs.get(i).isPendingUSSD()) {
                found = mPendingMMIs.get(i);
                break;
            }
        }

        if (found != null) {
            // Complete pending USSD
            if (isUssdError) {
                found.onUssdFinishedError();
            } else {
                found.onUssdFinished(ussdMessage, isUssdRequest);
            }
        } else if (!isUssdError && !TextUtils.isEmpty(ussdMessage)) {
                // pending USSD not found
                // The network may initiate its own USSD request

                // ignore everything that isnt a Notify or a Request
                // also, discard if there is no message to present
                ImsPhoneMmiCode mmi;
                mmi = ImsPhoneMmiCode.newNetworkInitiatedUssd(ussdMessage,
                        isUssdRequest,
                        this);
                onNetworkInitiatedUssd(mmi);
        }
    }

    void onUssdComplete(ImsPhoneMmiCode mmi, @NonNull CommandException ex) {
        //Check if USSD CS fallback scenario and has valid pending MMI session.
        if (ex.getCommandError() == CommandException.Error.NO_NETWORK_FOUND &&
                mPendingMMIs.contains(mmi)) {
            logi("onUssdComplete: migrating USSD from IMS to CS");
            try {
                mDefaultPhone.migrateUssdFrom(this, mmi.getDialString(),
                                              mmi.getUssdCallbackReceiver());
                mPendingMMIs.remove(mmi);
                return;
            } catch (UnsupportedOperationException e) {
                //no-op
            }
        }
        onMMIDone(mmi);
    }

    /**
     * Removes the given MMI from the pending list and notifies
     * registrants that it is complete.
     * @param mmi MMI that is done
     */
    @UnsupportedAppUsage
    public void onMMIDone(ImsPhoneMmiCode mmi) {
        /* Only notify complete if it's on the pending list.
         * Otherwise, it's already been handled (eg, previously canceled).
         * The exception is cancellation of an incoming USSD-REQUEST, which is
         * not on the list.
         */
        logd("onMMIDone: mmi=" + mmi);
        if (mPendingMMIs.remove(mmi) || mmi.isUssdRequest() || mmi.isSsInfo()) {
            ResultReceiver receiverCallback = mmi.getUssdCallbackReceiver();
            if (receiverCallback != null) {
                int returnCode = (mmi.getState() ==  MmiCode.State.COMPLETE) ?
                        TelephonyManager.USSD_RETURN_SUCCESS : TelephonyManager.USSD_RETURN_FAILURE;
                sendUssdResponse(mmi.getDialString(), mmi.getMessage(), returnCode,
                        receiverCallback );
            } else {
                logv("onMMIDone: notifyRegistrants");
                mMmiCompleteRegistrants.notifyRegistrants(
                    new AsyncResult(null, mmi, null));
            }
        }
    }

    @Override
    public ArrayList<Connection> getHandoverConnection() {
        ArrayList<Connection> connList = new ArrayList<Connection>();
        // Add all foreground call connections
        connList.addAll(getForegroundCall().getConnections());
        // Add all background call connections
        connList.addAll(getBackgroundCall().getConnections());
        // Add all background call connections
        connList.addAll(getRingingCall().getConnections());
        if (connList.size() > 0) {
            return connList;
        } else {
            return null;
        }
    }

    @Override
    public void notifySrvccState(Call.SrvccState state) {
        mCT.notifySrvccState(state);
    }

    /* package */ void
    initiateSilentRedial() {
        String result = mLastDialString;
        AsyncResult ar = new AsyncResult(null, result, null);
        if (ar != null) {
            mSilentRedialRegistrants.notifyRegistrants(ar);
        }
    }

    @Override
    public void registerForSilentRedial(Handler h, int what, Object obj) {
        mSilentRedialRegistrants.addUnique(h, what, obj);
    }

    @Override
    public void unregisterForSilentRedial(Handler h) {
        mSilentRedialRegistrants.remove(h);
    }

    @Override
    public void registerForSuppServiceNotification(Handler h, int what, Object obj) {
        mSsnRegistrants.addUnique(h, what, obj);
    }

    @Override
    public void unregisterForSuppServiceNotification(Handler h) {
        mSsnRegistrants.remove(h);
    }

    @Override
    public int getSubId() {
        return mDefaultPhone.getSubId();
    }

    @Override
    public int getPhoneId() {
        return mDefaultPhone.getPhoneId();
    }

    private CallForwardInfo getCallForwardInfo(ImsCallForwardInfo info) {
        CallForwardInfo cfInfo = new CallForwardInfo();
        cfInfo.status = info.getStatus();
        cfInfo.reason = getCFReasonFromCondition(info.getCondition());
        cfInfo.toa = info.getToA();
        cfInfo.number = info.getNumber();
        cfInfo.timeSeconds = info.getTimeSeconds();
        //Check if the service class signifies Video call forward
        //As per 3GPP TS 29002 MAP Specification : Section 17.7.10, the BearerServiceCode for
        //"allDataCircuitAsynchronous" is '01010000' ( i.e. 80).
        //Hence, SERVICE_CLASS_DATA_SYNC (1<<4) and SERVICE_CLASS_PACKET (1<<6)
        //together make video service class.
        if(info.mServiceClass == (SERVICE_CLASS_DATA_SYNC + SERVICE_CLASS_PACKET)) {
            cfInfo.serviceClass = info.getServiceClass();
        } else {
            cfInfo.serviceClass = SERVICE_CLASS_VOICE;
        }
        return cfInfo;
    }

    @Override
    public String getLine1Number() {
        return mDefaultPhone.getLine1Number();
    }

    /**
     * Used to Convert ImsCallForwardInfo[] to CallForwardInfo[].
     * Update received call forward status to default IccRecords.
     */
    public CallForwardInfo[] handleCfQueryResult(ImsCallForwardInfo[] infos) {
        CallForwardInfo[] cfInfos = null;

        if (infos != null && infos.length != 0) {
            cfInfos = new CallForwardInfo[infos.length];
        }

        if (infos == null || infos.length == 0) {
            // Assume the default is not active
            // Set unconditional CFF in SIM to false
            setVoiceCallForwardingFlag(getIccRecords(), 1, false, null);
        } else {
            for (int i = 0, s = infos.length; i < s; i++) {
                if (infos[i].getCondition() == ImsUtInterface.CDIV_CF_UNCONDITIONAL) {
                    //Check if the service class signifies Video call forward
                    if (infos[i].mServiceClass == (SERVICE_CLASS_DATA_SYNC +
                                SERVICE_CLASS_PACKET)) {
                        setVideoCallForwardingPreference(infos[i].getStatus() == 1);
                        notifyCallForwardingIndicator();
                    } else {
                        setVoiceCallForwardingFlag(getIccRecords(), 1, (infos[i].getStatus() == 1),
                            infos[i].getNumber());
                    }
                }
                cfInfos[i] = getCallForwardInfo(infos[i]);
            }
        }

        return cfInfos;
    }

    private int[] handleCbQueryResult(ImsSsInfo[] infos) {
        int[] cbInfos = new int[1];
        cbInfos[0] = SERVICE_CLASS_NONE;

        if (infos[0].getStatus() == 1) {
            cbInfos[0] = SERVICE_CLASS_VOICE;
        }

        return cbInfos;
    }

    private int[] handleCwQueryResult(ImsSsInfo[] infos) {
        int[] cwInfos = new int[2];
        cwInfos[0] = 0;

        if (infos[0].getStatus() == 1) {
            cwInfos[0] = 1;
            cwInfos[1] = SERVICE_CLASS_VOICE;
        }

        return cwInfos;
    }

    private void
    sendResponse(Message onComplete, Object result, Throwable e) {
        if (onComplete != null) {
            CommandException ex = null;
            if (e != null) {
                ex = getCommandException(e);
            }
            AsyncResult.forMessage(onComplete, result, ex);
            onComplete.sendToTarget();
        }
    }

    private void updateDataServiceState() {
        if (mSS != null && mDefaultPhone.getServiceStateTracker() != null
                && mDefaultPhone.getServiceStateTracker().mSS != null) {
            ServiceState ss = mDefaultPhone.getServiceStateTracker().mSS;
            mSS.setDataRegState(ss.getDataRegistrationState());
            List<NetworkRegistrationInfo> nriList =
                    ss.getNetworkRegistrationInfoListForDomain(NetworkRegistrationInfo.DOMAIN_PS);
            for (NetworkRegistrationInfo nri : nriList) {
                mSS.addNetworkRegistrationInfo(nri);
            }

            mSS.setIwlanPreferred(ss.isIwlanPreferred());
            logd("updateDataServiceState: defSs = " + ss + " imsSs = " + mSS);
        }
    }

    @Override
    public void handleMessage(Message msg) {
        AsyncResult ar = (AsyncResult) msg.obj;

        if (DBG) logd("handleMessage what=" + msg.what);
        switch (msg.what) {
            case EVENT_SET_CALL_FORWARD_DONE:
                Cf cf = (Cf) ar.userObj;
                if (cf.mIsCfu && ar.exception == null) {
                    if (cf.mServiceClass == (SERVICE_CLASS_DATA_SYNC + SERVICE_CLASS_PACKET)) {
                        setVideoCallForwardingPreference(msg.arg1 == 1);
                        notifyCallForwardingIndicator();
                    } else if (cf.mServiceClass == SERVICE_CLASS_VOICE) {
                        setVoiceCallForwardingFlag(getIccRecords(), 1, msg.arg1 == 1, cf.mSetCfNumber);
                    }
                }
                sendResponse(cf.mOnComplete, null, ar.exception);
                break;

            case EVENT_GET_CALL_FORWARD_DONE:
                CallForwardInfo[] cfInfos = null;
                if (ar.exception == null) {
                    cfInfos = handleCfQueryResult((ImsCallForwardInfo[])ar.result);
                }
                sendResponse((Message) ar.userObj, cfInfos, ar.exception);
                break;

            case EVENT_GET_CALL_BARRING_DONE:
            case EVENT_GET_CALL_WAITING_DONE:
                int[] ssInfos = null;
                if (ar.exception == null) {
                    if (msg.what == EVENT_GET_CALL_BARRING_DONE) {
                        ssInfos = handleCbQueryResult((ImsSsInfo[])ar.result);
                    } else if (msg.what == EVENT_GET_CALL_WAITING_DONE) {
                        ssInfos = handleCwQueryResult((ImsSsInfo[])ar.result);
                    }
                }
                sendResponse((Message) ar.userObj, ssInfos, ar.exception);
                break;

            case EVENT_GET_CLIR_DONE:
                ImsSsInfo ssInfo = (ImsSsInfo) ar.result;
                int[] clirInfo = null;
                if (ssInfo != null) {
                    // Unfortunately callers still use the old {n,m} format of ImsSsInfo, so return
                    // that for compatibility
                    clirInfo = ssInfo.getCompatArray(ImsSsData.SS_CLIR);
                }
                sendResponse((Message) ar.userObj, clirInfo, ar.exception);
                break;

            case EVENT_SET_CLIR_DONE:
                if (ar.exception == null) {
                    saveClirSetting(msg.arg1);
                }
                 // (Intentional fallthrough)
            case EVENT_SET_CALL_BARRING_DONE:
            case EVENT_SET_CALL_WAITING_DONE:
                sendResponse((Message) ar.userObj, null, ar.exception);
                break;

            case EVENT_DEFAULT_PHONE_DATA_STATE_CHANGED:
                if (DBG) logd("EVENT_DEFAULT_PHONE_DATA_STATE_CHANGED");
                updateDataServiceState();
                break;

            case EVENT_SERVICE_STATE_CHANGED:
                if (VDBG) logd("EVENT_SERVICE_STATE_CHANGED");
                ar = (AsyncResult) msg.obj;
                ServiceState newServiceState = (ServiceState) ar.result;
                updateRoamingState(newServiceState);
                break;
            case EVENT_VOICE_CALL_ENDED:
                if (DBG) logd("Voice call ended. Handle pending updateRoamingState.");
                mCT.unregisterForVoiceCallEnded(this);
                // Get the current unmodified ServiceState from the tracker, as it has more info
                // about the cell roaming state.
                ServiceStateTracker sst = getDefaultPhone().getServiceStateTracker();
                if (sst != null) {
                    updateRoamingState(sst.mSS);
                }
                break;
            case EVENT_INITIATE_VOLTE_SILENT_REDIAL: {
                if (VDBG) logd("EVENT_INITIATE_VOLTE_SILENT_REDIAL");
                ar = (AsyncResult) msg.obj;
                if (ar.exception == null && ar.result != null) {
                    SilentRedialParam result = (SilentRedialParam) ar.result;
                    String dialString = result.dialString;
                    int causeCode = result.causeCode;
                    DialArgs dialArgs = result.dialArgs;
                    if (VDBG) logd("dialString=" + dialString + " causeCode=" + causeCode);

                    try {
                        Connection cn = dial(dialString,
                                updateDialArgsForVolteSilentRedial(dialArgs, causeCode));
                        Rlog.d(LOG_TAG, "Notify volte redial connection changed cn: " + cn);
                        if (mDefaultPhone != null) {
                            // don't care it is null or not.
                            mDefaultPhone.notifyRedialConnectionChanged(cn);
                        }
                    } catch (CallStateException e) {
                        Rlog.e(LOG_TAG, "volte silent redial failed: " + e);
                        if (mDefaultPhone != null) {
                            mDefaultPhone.notifyRedialConnectionChanged(null);
                        }
                    }
                } else {
                    if (VDBG) logd("EVENT_INITIATE_VOLTE_SILENT_REDIAL" +
                                   " has exception or empty result");
                }
                break;
            }

            default:
                super.handleMessage(msg);
                break;
        }
    }

    @Override
    public boolean isInEmergencyCall() {
        return mCT.isInEmergencyCall();
    }

    @UnsupportedAppUsage
    private void handleEnterEmergencyCallbackMode() {
    }

    @UnsupportedAppUsage
    @Override
    protected void handleExitEmergencyCallbackMode() {
    }

    @UnsupportedAppUsage
    @Override
    public void setOnEcbModeExitResponse(Handler h, int what, Object obj) {
    }

    public void onFeatureCapabilityChanged() {
        mDefaultPhone.getServiceStateTracker().onImsCapabilityChanged();
    }

    @Override
    public boolean isImsCapabilityAvailable(int capability, int regTech) throws ImsException {
        return mCT.isImsCapabilityAvailable(capability, regTech);
    }

    @UnsupportedAppUsage
    @Override
    public boolean isVolteEnabled() {
        return mCT.isVolteEnabled();
    }

    @Override
    public boolean isWifiCallingEnabled() {
        return mCT.isVowifiEnabled();
    }

    @Override
    public boolean isVideoEnabled() {
        return mCT.isVideoCallEnabled();
    }

    @Override
    public int getImsRegistrationTech() {
        return mCT.getImsRegistrationTech();
    }

    @Override
    public void getImsRegistrationTech(Consumer<Integer> callback) {
        mCT.getImsRegistrationTech(callback);
    }

    @Override
    public void getImsRegistrationState(Consumer<Integer> callback) {
        callback.accept(mImsMmTelRegistrationHelper.getImsRegistrationState());
    }

    @Override
    public Phone getDefaultPhone() {
        return mDefaultPhone;
    }

    @Override
    public boolean isImsRegistered() {
        return mImsMmTelRegistrationHelper.isImsRegistered();
    }

    // Not used, but not removed due to UnsupportedAppUsage tag.
    @UnsupportedAppUsage
    public void setImsRegistered(boolean isRegistered) {
        mImsMmTelRegistrationHelper.updateRegistrationState(
                isRegistered ? RegistrationManager.REGISTRATION_STATE_REGISTERED :
                        RegistrationManager.REGISTRATION_STATE_NOT_REGISTERED);
    }

    public void setImsRegistrationState(@RegistrationManager.ImsRegistrationState int value) {
        if (DBG) logd("setImsRegistrationState: " + value);
        mImsMmTelRegistrationHelper.updateRegistrationState(value);
    }

    @Override
    public void callEndCleanupHandOverCallIfAny() {
        mCT.callEndCleanupHandOverCallIfAny();
    }

    private BroadcastReceiver mResultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Add notification only if alert was not shown by WfcSettings
            if (getResultCode() == Activity.RESULT_OK) {
                // Default result code (as passed to sendOrderedBroadcast)
                // means that intent was not received by WfcSettings.

                CharSequence title =
                        intent.getCharSequenceExtra(EXTRA_WFC_REGISTRATION_FAILURE_TITLE);
                CharSequence messageAlert =
                        intent.getCharSequenceExtra(EXTRA_WFC_REGISTRATION_FAILURE_MESSAGE);
                CharSequence messageNotification =
                        intent.getCharSequenceExtra(EXTRA_KEY_NOTIFICATION_MESSAGE);

                Intent resultIntent = new Intent(Intent.ACTION_MAIN);
                // Note: If the classname below is ever removed, the call to
                // PendingIntent.getActivity should also specify FLAG_IMMUTABLE to ensure the
                // pending intent cannot be tampered with.
                resultIntent.setClassName("com.android.settings",
                        "com.android.settings.Settings$WifiCallingSettingsActivity");
                resultIntent.putExtra(EXTRA_KEY_ALERT_SHOW, true);
                resultIntent.putExtra(EXTRA_WFC_REGISTRATION_FAILURE_TITLE, title);
                resultIntent.putExtra(EXTRA_WFC_REGISTRATION_FAILURE_MESSAGE, messageAlert);
                PendingIntent resultPendingIntent =
                        PendingIntent.getActivity(
                                mContext,
                                0,
                                resultIntent,
                                // Note: Since resultIntent above specifies an explicit class name
                                // we do not need to specify PendingIntent.FLAG_IMMUTABLE here.
                                PendingIntent.FLAG_UPDATE_CURRENT
                        );

                final Notification notification = new Notification.Builder(mContext)
                                .setSmallIcon(android.R.drawable.stat_sys_warning)
                                .setContentTitle(title)
                                .setContentText(messageNotification)
                                .setAutoCancel(true)
                                .setContentIntent(resultPendingIntent)
                                .setStyle(new Notification.BigTextStyle()
                                .bigText(messageNotification))
                                .setChannelId(NotificationChannelController.CHANNEL_ID_WFC)
                                .build();
                final String notificationTag = "wifi_calling";
                final int notificationId = 1;

                NotificationManager notificationManager =
                        (NotificationManager) mContext.getSystemService(
                                Context.NOTIFICATION_SERVICE);
                notificationManager.notify(notificationTag, notificationId,
                        notification);
            }
        }
    };

    /**
     * Show notification in case of some error codes.
     */
    public void processDisconnectReason(ImsReasonInfo imsReasonInfo) {
        if (imsReasonInfo.mCode == imsReasonInfo.CODE_REGISTRATION_ERROR
                && imsReasonInfo.mExtraMessage != null) {
            // Suppress WFC Registration notifications if WFC is not enabled by the user.
            if (ImsManager.getInstance(mContext, mPhoneId).isWfcEnabledByUser()) {
                processWfcDisconnectForNotification(imsReasonInfo);
            }
        }
    }

    // Processes an IMS disconnect cause for possible WFC registration errors and optionally
    // disable WFC.
    private void processWfcDisconnectForNotification(ImsReasonInfo imsReasonInfo) {
        CarrierConfigManager configManager =
                (CarrierConfigManager) mContext.getSystemService(Context.CARRIER_CONFIG_SERVICE);
        if (configManager == null) {
            loge("processDisconnectReason: CarrierConfigManager is not ready");
            return;
        }
        PersistableBundle pb = configManager.getConfigForSubId(getSubId());
        if (pb == null) {
            loge("processDisconnectReason: no config for subId " + getSubId());
            return;
        }
        final String[] wfcOperatorErrorCodes =
                pb.getStringArray(
                        CarrierConfigManager.KEY_WFC_OPERATOR_ERROR_CODES_STRING_ARRAY);
        if (wfcOperatorErrorCodes == null) {
            // no operator-specific error codes
            return;
        }

        final String[] wfcOperatorErrorAlertMessages =
                mContext.getResources().getStringArray(
                        com.android.internal.R.array.wfcOperatorErrorAlertMessages);
        final String[] wfcOperatorErrorNotificationMessages =
                mContext.getResources().getStringArray(
                        com.android.internal.R.array.wfcOperatorErrorNotificationMessages);

        for (int i = 0; i < wfcOperatorErrorCodes.length; i++) {
            String[] codes = wfcOperatorErrorCodes[i].split("\\|");
            if (codes.length != 2) {
                loge("Invalid carrier config: " + wfcOperatorErrorCodes[i]);
                continue;
            }

            // Match error code.
            if (!imsReasonInfo.mExtraMessage.startsWith(
                    codes[0])) {
                continue;
            }
            // If there is no delimiter at the end of error code string
            // then we need to verify that we are not matching partial code.
            // EXAMPLE: "REG9" must not match "REG99".
            // NOTE: Error code must not be empty.
            int codeStringLength = codes[0].length();
            char lastChar = codes[0].charAt(codeStringLength - 1);
            if (Character.isLetterOrDigit(lastChar)) {
                if (imsReasonInfo.mExtraMessage.length() > codeStringLength) {
                    char nextChar = imsReasonInfo.mExtraMessage.charAt(codeStringLength);
                    if (Character.isLetterOrDigit(nextChar)) {
                        continue;
                    }
                }
            }

            final CharSequence title = mContext.getText(
                    com.android.internal.R.string.wfcRegErrorTitle);

            int idx = Integer.parseInt(codes[1]);
            if (idx < 0
                    || idx >= wfcOperatorErrorAlertMessages.length
                    || idx >= wfcOperatorErrorNotificationMessages.length) {
                loge("Invalid index: " + wfcOperatorErrorCodes[i]);
                continue;
            }
            String messageAlert = imsReasonInfo.mExtraMessage;
            String messageNotification = imsReasonInfo.mExtraMessage;
            if (!wfcOperatorErrorAlertMessages[idx].isEmpty()) {
                messageAlert = String.format(
                        wfcOperatorErrorAlertMessages[idx],
                        imsReasonInfo.mExtraMessage); // Fill IMS error code into alert message
            }
            if (!wfcOperatorErrorNotificationMessages[idx].isEmpty()) {
                messageNotification = String.format(
                        wfcOperatorErrorNotificationMessages[idx],
                        imsReasonInfo.mExtraMessage); // Fill IMS error code into notification
            }

            // If WfcSettings are active then alert will be shown
            // otherwise notification will be added.
            Intent intent = new Intent(
                    android.telephony.ims.ImsManager.ACTION_WFC_IMS_REGISTRATION_ERROR);
            intent.putExtra(EXTRA_WFC_REGISTRATION_FAILURE_TITLE, title);
            intent.putExtra(EXTRA_WFC_REGISTRATION_FAILURE_MESSAGE, messageAlert);
            intent.putExtra(EXTRA_KEY_NOTIFICATION_MESSAGE, messageNotification);
            mContext.sendOrderedBroadcast(intent, null, mResultReceiver,
                    null, Activity.RESULT_OK, null, null);

            // We can only match a single error code
            // so should break the loop after a successful match.
            break;
        }
    }

    @UnsupportedAppUsage
    @Override
    public boolean isUtEnabled() {
        return mCT.isUtEnabled();
    }

    @Override
    public void sendEmergencyCallStateChange(boolean callActive) {
        mDefaultPhone.sendEmergencyCallStateChange(callActive);
    }

    @Override
    public void setBroadcastEmergencyCallStateChanges(boolean broadcast) {
        mDefaultPhone.setBroadcastEmergencyCallStateChanges(broadcast);
    }

    @Override
    public void notifyCallForwardingIndicator() {
        mDefaultPhone.notifyCallForwardingIndicator();
    }

    @VisibleForTesting
    public PowerManager.WakeLock getWakeLock() {
        return mWakeLock;
    }

    /**
     * Update roaming state and WFC mode in the following situations:
     *     1) voice is in service.
     *     2) data is in service and it is not IWLAN (if in legacy mode).
     * @param ss non-null ServiceState
     */
    private void updateRoamingState(ServiceState ss) {
        if (ss == null) {
            loge("updateRoamingState: null ServiceState!");
            return;
        }
        boolean newRoamingState = ss.getRoaming();
        // Do not recalculate if there is no change to state.
        if (mRoaming == newRoamingState) {
            return;
        }
        boolean isInService = (ss.getState() == ServiceState.STATE_IN_SERVICE
                || ss.getDataRegistrationState() == ServiceState.STATE_IN_SERVICE);
        // If we are not IN_SERVICE for voice or data, ignore change roaming state, as we always
        // move to home in this case.
        if (!isInService) {
            logi("updateRoamingState: we are OUT_OF_SERVICE, ignoring roaming change.");
            return;
        }
        // We ignore roaming changes when moving to IWLAN because it always sets the roaming
        // mode to home and masks the actual cellular roaming status if voice is not registered. If
        // we just moved to IWLAN because WFC roaming mode is IWLAN preferred and WFC home mode is
        // cell preferred, we can get into a condition where the modem keeps bouncing between
        // IWLAN->cell->IWLAN->cell...
        if (isCsNotInServiceAndPsWwanReportingWlan(ss)) {
            logi("updateRoamingState: IWLAN masking roaming, ignore roaming change.");
            return;
        }
        if (mCT.getState() == PhoneConstants.State.IDLE) {
            if (DBG) logd("updateRoamingState now: " + newRoamingState);
            mRoaming = newRoamingState;
            CarrierConfigManager configManager = (CarrierConfigManager)
                    getContext().getSystemService(Context.CARRIER_CONFIG_SERVICE);
            // Don't set wfc mode if carrierconfig has not loaded. It will be set by GsmCdmaPhone
            // when receives ACTION_CARRIER_CONFIG_CHANGED broadcast.
            if (configManager != null && CarrierConfigManager.isConfigForIdentifiedCarrier(
                    configManager.getConfigForSubId(getSubId()))) {
                ImsManager imsManager = ImsManager.getInstance(mContext, mPhoneId);
                imsManager.setWfcMode(imsManager.getWfcMode(newRoamingState), newRoamingState);
            }
        } else {
            if (DBG) logd("updateRoamingState postponed: " + newRoamingState);
            mCT.registerForVoiceCallEnded(this, EVENT_VOICE_CALL_ENDED, null);
        }
    }

    /**
     * In legacy mode, data registration will report IWLAN when we are using WLAN for data,
     * effectively masking the true roaming state of the device if voice is not registered.
     *
     * @return true if we are reporting not in service for CS domain over WWAN transport and WLAN
     * for PS domain over WWAN transport.
     */
    private boolean isCsNotInServiceAndPsWwanReportingWlan(ServiceState ss) {
        TransportManager tm = mDefaultPhone.getTransportManager();
        // We can not get into this condition if we are in AP-Assisted mode.
        if (tm == null || !tm.isInLegacyMode()) {
            return false;
        }
        NetworkRegistrationInfo csInfo = ss.getNetworkRegistrationInfo(
                NetworkRegistrationInfo.DOMAIN_CS, AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        NetworkRegistrationInfo psInfo = ss.getNetworkRegistrationInfo(
                NetworkRegistrationInfo.DOMAIN_PS, AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        // We will return roaming state correctly if the CS domain is in service because
        // ss.getRoaming() returns isVoiceRoaming||isDataRoaming result and isDataRoaming==false
        // when the modem reports IWLAN RAT.
        return psInfo != null && csInfo != null && !csInfo.isInService()
                && psInfo.getAccessNetworkTechnology() == TelephonyManager.NETWORK_TYPE_IWLAN;
    }

    private BroadcastReceiver mRttReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mPhoneId != intent.getIntExtra(QtiImsUtils.EXTRA_PHONE_ID, 0)) {
                Rlog.d(LOG_TAG, "RTT: intent - " + intent.getAction() +
                        " received but not intended for phoneId: " + mPhoneId);
                return;
            }
            if (QtiImsUtils.ACTION_SEND_RTT_TEXT.equals(intent.getAction())) {
                Rlog.d(LOG_TAG, "RTT: Received ACTION_SEND_RTT_TEXT");
                String data = intent.getStringExtra(QtiImsUtils.RTT_TEXT_VALUE);
                sendRttMessage(data);
            } else if (QtiImsUtils.ACTION_RTT_OPERATION.equals(intent.getAction())) {
                Rlog.d(LOG_TAG, "RTT: Received ACTION_RTT_OPERATION");
                int data = intent.getIntExtra(QtiImsUtils.RTT_OPERATION_TYPE, 0);
                checkIfModifyRequestOrResponse(data);
            } else {
                Rlog.d(LOG_TAG, "RTT: unknown intent");
            }
        }
    };

    /**
     * Sends Rtt message
     * Rtt Message can be sent only when -
     * operating mode is RTT_FULL and for non-VT calls only based on config
     *
     * @param data The Rtt text to be sent
     */
    public void sendRttMessage(String data) {
        ImsCall imsCall = getForegroundCall().getImsCall();
        if (imsCall == null) {
            Rlog.d(LOG_TAG, "RTT: imsCall null");
            return;
        }

        if (!imsCall.isRttCall()) {
            Rlog.d(LOG_TAG, "RTT: imsCall not RTT capable");
            return;
        }

        // Check for empty message
        if (TextUtils.isEmpty(data)) {
            Rlog.d(LOG_TAG, "RTT: Text null");
            return;
        }

        if (!isRttVtCallAllowed(imsCall)) {
            Rlog.d(LOG_TAG, "RTT: VT call is not allowed");
            return;
        }

        Rlog.d(LOG_TAG, "RTT: sendRttMessage = " + data);
        imsCall.sendRttMessage(data);
    }

    /**
     * Sends RTT Upgrade request
     *
     * @param to: expected profile
     */
    public void sendRttModifyRequest(ImsCallProfile to) {
        Rlog.d(LOG_TAG, "RTT: sendRttModifyRequest");
        ImsCall imsCall = getForegroundCall().getImsCall();
        if (imsCall == null) {
            Rlog.d(LOG_TAG, "RTT: imsCall null");
            return;
        }

        try {
            imsCall.sendRttModifyRequest(to);
        } catch (ImsException e) {
            Rlog.e(LOG_TAG, "RTT: sendRttModifyRequest exception = " + e);
        }
    }

    /**
     * Sends RTT Upgrade response
     *
     * @param data : response for upgrade
     */
    public void sendRttModifyResponse(boolean response) {
        ImsCall imsCall = getForegroundCall().getImsCall();
        if (imsCall == null) {
            Rlog.d(LOG_TAG, "RTT: imsCall null");
            return;
        }

        if (!isRttVtCallAllowed(imsCall)) {
            Rlog.d(LOG_TAG, "RTT: Not allowed for VT");
            return;
        }

        Rlog.d(LOG_TAG, "RTT: sendRttModifyResponse = " + (response ? "ACCEPTED" : "REJECTED"));
        imsCall.sendRttModifyResponse(response);
    }

    // Utility to check if the value coming in intent is for upgrade initiate or upgrade response
    private void checkIfModifyRequestOrResponse(int data) {
        if (!(isRttSupported() && (isRttOn() || isInEmergencyCall())) ||
                   (ImsPhoneCall.State.ACTIVE != getForegroundCall().getState())) {
            Rlog.d(LOG_TAG, "RTT: Request or Response not allowed");
            return;
        }

        Rlog.d(LOG_TAG, "RTT: checkIfModifyRequestOrResponse data =  " + data);
        switch (data) {
            case QtiImsUtils.RTT_UPGRADE_INITIATE:
                ImsCall currentCall = getForegroundCall().getImsCall();
                boolean rttUpgradeSupported = QtiImsUtils.isRttUpgradeSupported(mPhoneId, mContext);
                boolean rttVtSupported = QtiImsUtils.isRttSupportedOnVtCalls(mPhoneId, mContext);
                boolean isVideoCall = (currentCall != null) ? currentCall.isVideoCall() : false;
                if (!rttUpgradeSupported || (isVideoCall && !rttVtSupported)) {
                    Rlog.d(LOG_TAG, "RTT: upgrade not supported");
                    return;
                }
                // Rtt Upgrade means enable Rtt
                packRttModifyRequestToProfile(ImsStreamMediaProfile.RTT_MODE_FULL);
                break;
            case QtiImsUtils.RTT_DOWNGRADE_INITIATE:
                if (!QtiImsUtils.isRttDowngradeSupported(mPhoneId, mContext)) {
                    Rlog.d(LOG_TAG, "RTT: downgrade not supported");
                    return;
                }
                // Rtt downrade means disable Rtt
                packRttModifyRequestToProfile(ImsStreamMediaProfile.RTT_MODE_DISABLED);
                break;
            case QtiImsUtils.RTT_UPGRADE_CONFIRM:
                sendRttModifyResponse(true);
                break;
            case QtiImsUtils.RTT_UPGRADE_REJECT:
                sendRttModifyResponse(false);
                break;
            case QtiImsUtils.SHOW_RTT_KEYBOARD:
                ImsCall imsCall = getForegroundCall().getImsCall();
                if (imsCall != null && isRttSupported() &&
                    QtiImsUtils.shallShowRttVisibilitySetting(mPhoneId, mContext) &&
                    !imsCall.isRttCall()) {
                    packRttModifyRequestToProfile(ImsStreamMediaProfile.RTT_MODE_FULL);
                }
                break;
             case QtiImsUtils.HIDE_RTT_KEYBOARD:
                //no-op
                break;
        }
    }

    private void packRttModifyRequestToProfile(int data) {
        if (getForegroundCall().getImsCall() == null) {
            Rlog.d(LOG_TAG, "RTT: cannot send rtt modify request");
            return;
        }

        ImsCallProfile fromProfile = getForegroundCall().getImsCall().getCallProfile();
        ImsCallProfile toProfile = new ImsCallProfile(fromProfile.mServiceType,
                fromProfile.mCallType);
        toProfile.mMediaProfile.setRttMode(data);

        Rlog.d(LOG_TAG, "RTT: packRttModifyRequestToProfile");
        sendRttModifyRequest(toProfile);
    }

    public boolean isRttSupported() {
        if (!QtiImsUtils.isRttSupported(mPhoneId, mContext)) {
            Rlog.d(LOG_TAG, "RTT: RTT is not supported");
            return false;
        }
        Rlog.d(LOG_TAG, "RTT: rtt supported = " +
                QtiImsUtils.isRttSupported(mPhoneId, mContext) + ", Rtt mode = " +
                QtiImsUtils.getRttOperatingMode(mContext, mPhoneId));
        return true;
    }

    /*
     * Rtt for VT calls is not supported for certain operators
     * Check the config and process the request
     */
    public boolean isRttVtCallAllowed(ImsCall call) {
        return !(call.getCallProfile().isVideoCall() &&
                 !QtiImsUtils.isRttSupportedOnVtCalls(mPhoneId, mContext));
    }

    public boolean isRttOn() {
        if (!QtiImsUtils.isRttOn(mContext, mPhoneId)) {
            Rlog.d(LOG_TAG, "RTT: RTT is off");
            return false;
        }
        Rlog.d(LOG_TAG, "RTT: Rtt on = " + QtiImsUtils.isRttOn(mContext, mPhoneId));
        return true;
    }

    public RegistrationManager.RegistrationCallback getImsMmTelRegistrationCallback() {
        return mImsMmTelRegistrationHelper.getCallback();
    }

    /**
     * Reset the IMS registration state.
     */
    public void resetImsRegistrationState() {
        if (DBG) logd("resetImsRegistrationState");
        mImsMmTelRegistrationHelper.reset();
    }

    private ImsRegistrationCallbackHelper.ImsRegistrationUpdate mMmTelRegistrationUpdate = new
            ImsRegistrationCallbackHelper.ImsRegistrationUpdate() {
        @Override
        public void handleImsRegistered(int imsRadioTech) {
            if (DBG) {
                logd("onImsMmTelConnected imsRadioTech="
                        + AccessNetworkConstants.transportTypeToString(imsRadioTech));
            }
            mRegLocalLog.log("onImsMmTelConnected imsRadioTech="
                    + AccessNetworkConstants.transportTypeToString(imsRadioTech));
            setServiceState(ServiceState.STATE_IN_SERVICE);
            mMetrics.writeOnImsConnectionState(mPhoneId, ImsConnectionState.State.CONNECTED, null);
        }

        @Override
        public void handleImsRegistering(int imsRadioTech) {
            if (DBG) {
                logd("onImsMmTelProgressing imsRadioTech="
                        + AccessNetworkConstants.transportTypeToString(imsRadioTech));
            }
            mRegLocalLog.log("onImsMmTelProgressing imsRadioTech="
                    + AccessNetworkConstants.transportTypeToString(imsRadioTech));
            setServiceState(ServiceState.STATE_OUT_OF_SERVICE);
            mMetrics.writeOnImsConnectionState(mPhoneId, ImsConnectionState.State.PROGRESSING,
                    null);
        }

        @Override
        public void handleImsUnregistered(ImsReasonInfo imsReasonInfo) {
            if (DBG) logd("onImsMmTelDisconnected imsReasonInfo=" + imsReasonInfo);
            mRegLocalLog.log("onImsMmTelDisconnected imsRadioTech=" + imsReasonInfo);
            setServiceState(ServiceState.STATE_OUT_OF_SERVICE);
            processDisconnectReason(imsReasonInfo);
            mMetrics.writeOnImsConnectionState(mPhoneId, ImsConnectionState.State.DISCONNECTED,
                    imsReasonInfo);
        }

        @Override
        public void handleImsSubscriberAssociatedUriChanged(Uri[] uris) {
            if (DBG) logd("handleImsSubscriberAssociatedUriChanged");
            setCurrentSubscriberUris(uris);
        }
    };

    public IccRecords getIccRecords() {
        return mDefaultPhone.getIccRecords();
    }

    public DialArgs updateDialArgsForVolteSilentRedial(DialArgs dialArgs, int causeCode) {
        if (dialArgs != null) {
            ImsPhone.ImsDialArgs.Builder imsDialArgsBuilder;
            if (dialArgs instanceof ImsPhone.ImsDialArgs) {
                imsDialArgsBuilder = ImsPhone.ImsDialArgs.Builder
                                      .from((ImsPhone.ImsDialArgs) dialArgs);
            } else {
                imsDialArgsBuilder = ImsPhone.ImsDialArgs.Builder
                                      .from(dialArgs);
            }
            Bundle extras = new Bundle(dialArgs.intentExtras);
            if (causeCode == CallFailCause.EMC_REDIAL_ON_VOWIFI && isWifiCallingEnabled()) {
                extras.putString(ImsCallProfile.EXTRA_CALL_RAT_TYPE,
                        String.valueOf(ServiceState.RIL_RADIO_TECHNOLOGY_IWLAN));
                logd("trigger VoWifi emergency call");
                imsDialArgsBuilder.setIntentExtras(extras);
            } else if (causeCode == CallFailCause.EMC_REDIAL_ON_IMS) {
                logd("trigger VoLte emergency call");
            }
            return imsDialArgsBuilder.build();
        }
        return new DialArgs.Builder<>().build();
    }

    @Override
    public VoiceCallSessionStats getVoiceCallSessionStats() {
        return mDefaultPhone.getVoiceCallSessionStats();
    }

    public boolean hasAliveCall() {
        return (getForegroundCall().getState() != Call.State.IDLE ||
                getBackgroundCall().getState() != Call.State.IDLE);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter printWriter, String[] args) {
        IndentingPrintWriter pw = new IndentingPrintWriter(printWriter, "  ");
        pw.println("ImsPhone extends:");
        super.dump(fd, pw, args);
        pw.flush();

        pw.println("ImsPhone:");
        pw.println("  mDefaultPhone = " + mDefaultPhone);
        pw.println("  mPendingMMIs = " + mPendingMMIs);
        pw.println("  mPostDialHandler = " + mPostDialHandler);
        pw.println("  mSS = " + mSS);
        pw.println("  mWakeLock = " + mWakeLock);
        pw.println("  mIsPhoneInEcmState = " + EcbmHandler.getInstance().isInEcm());
        pw.println("  mSilentRedialRegistrants = " + mSilentRedialRegistrants);
        pw.println("  mImsMmTelRegistrationState = "
                + mImsMmTelRegistrationHelper.getImsRegistrationState());
        pw.println("  mRoaming = " + mRoaming);
        pw.println("  mSsnRegistrants = " + mSsnRegistrants);
        pw.println(" Registration Log:");
        pw.increaseIndent();
        mRegLocalLog.dump(pw);
        pw.decreaseIndent();
        pw.flush();
    }

    private void logi(String s) {
        Rlog.i(LOG_TAG, "[" + mPhoneId + "] " + s);
    }

    private void logv(String s) {
        Rlog.v(LOG_TAG, "[" + mPhoneId + "] " + s);
    }

    private void logd(String s) {
        Rlog.d(LOG_TAG, "[" + mPhoneId + "] " + s);
    }

    private void loge(String s) {
        Rlog.e(LOG_TAG, "[" + mPhoneId + "] " + s);
    }
}
