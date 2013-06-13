/*
 * Copyright (c) 2012-2013, The Linux Foundation. All rights reserved.
 * Not a Contribution.
 *
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.internal.telephony;

import android.content.Context;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.RegistrantList;
import android.os.SystemProperties;
import android.telephony.ServiceState;
import android.telephony.MSimTelephonyManager;
import android.util.Log;

import com.android.internal.telephony.MSimConstants;
import com.android.internal.telephony.sip.SipPhone;

import java.util.ArrayList;
import java.util.List;


/**
 * @hide
 *
 * ExtCallManager class provides an abstract layer for PhoneApp to access
 * and control calls. It implements Phone interface.
 *
 * ExtCallManager provides call and connection control as well as
 * channel capability.
 *
 * There are three categories of APIs ExtCallManager provided and
 * many of this APIs act on the active subscription or on the provided
 * subscription.
 *
 *  1. Call control and operation, such as dial() and hangup()
 *  2. Channel capabilities, such as CanConference()
 *  3. Register notification
 *
 *  Note:
 *  1.Active sub is a subscription which is currently visible to the user, on
 *    single sim targets by default this active sub set to 0.
 *  2.Subscription value of IMS and SIP phone is set to 0.
 */
public class ExtCallManager extends CallManager {

    private static final String LOG_TAG ="ExtCallManager";
    private static final boolean DBG = true;
    private static final boolean VDBG = false;

    // Holds the current active SUB, all actions would be
    // taken on this sub.
    private static int mActiveSub = 0;

    private long [] vsidVoiceDef = {AudioSystem.VOICE_VSID, AudioSystem.VOICE2_VSID};
    private long [] vsidVoice= {-1, -1};

    // Holds the LCH status of subscription
    private int [] mLchStatus = {0, 0};

    /**
     * get singleton instance of ExtCallManager
     * @return ExtCallManager
     */
    public static CallManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ExtCallManager();
        }
        return INSTANCE;
    }

    /**
     * get Phone object corresponds to subscription
     * @return Phone
     */
    private Phone getPhone(int subscription) {
        Phone p = null;
        for (Phone phone : mPhones) {
            if (phone.getSubscription() == subscription) {
                p = phone;
                break;
            }
        }
        return p;
    }

    /**
     * Get current coarse-grained voice call state on active subscription.
     * If the Call Manager has an active call and call waiting occurs,
     * then the phone state is RINGING not OFFHOOK
     *
     */
    @Override
    public PhoneConstants.State getState() {
        return getState(getActiveSubscription());
    }

    /**
     * Get current coarse-grained voice call state on a subscription.
     * If the Call Manager has an active call and call waiting occurs,
     * then the phone state is RINGING not OFFHOOK
     *
     */
    @Override
    public PhoneConstants.State getState(int subscription) {
        PhoneConstants.State s = PhoneConstants.State.IDLE;

        for (Phone phone : mPhones) {
            if (phone.getSubscription() == subscription) {
                if (phone.getState() == PhoneConstants.State.RINGING) {
                    s = PhoneConstants.State.RINGING;
                } else if (phone.getState() == PhoneConstants.State.OFFHOOK) {
                    if (s == PhoneConstants.State.IDLE) s = PhoneConstants.State.OFFHOOK;
                }
            }
        }
        return s;
    }

    /**
     * @return the Phone service state corresponds to subscription
     */
    @Override
    public int getServiceState(int subscription) {
        int resultState = ServiceState.STATE_OUT_OF_SERVICE;

        for (Phone phone : mPhones) {
            if (phone.getSubscription() == subscription) {
                int serviceState = phone.getServiceState().getState();
                if (serviceState == ServiceState.STATE_IN_SERVICE) {
                    // IN_SERVICE has the highest priority
                    resultState = serviceState;
                    break;
                } else if (serviceState == ServiceState.STATE_OUT_OF_SERVICE) {
                    // OUT_OF_SERVICE replaces EMERGENCY_ONLY and POWER_OFF
                    // Note: EMERGENCY_ONLY is not in use at this moment
                    if ( resultState == ServiceState.STATE_EMERGENCY_ONLY ||
                            resultState == ServiceState.STATE_POWER_OFF) {
                        resultState = serviceState;
                    }
                } else if (serviceState == ServiceState.STATE_EMERGENCY_ONLY) {
                    if (resultState == ServiceState.STATE_POWER_OFF) {
                        resultState = serviceState;
                    }
                }
            }
        }
        return resultState;
    }

    /**
     * @return the phone associated with the foreground call
     * of a particular subscription
     */
    @Override
    public Phone getFgPhone(int subscription) {
        return getActiveFgCall(subscription).getPhone();
    }

    /**
     * @return the phone associated with the background call
     * of a particular subscription
     */
    @Override
    public Phone getBgPhone(int subscription) {
        return getFirstActiveBgCall(subscription).getPhone();
    }

    /**
     * @return the phone associated with the ringing call
     * of a particular subscription
     */
    @Override
    public Phone getRingingPhone(int subscription) {
        return getFirstActiveRingingCall(subscription).getPhone();
    }

    @Override
    public Phone getPhoneInCall(int subscription) {
        Phone phone = null;
        if (!getFirstActiveRingingCall(subscription).isIdle()) {
            phone = getFirstActiveRingingCall(subscription).getPhone();
        } else if (!getActiveFgCall(subscription).isIdle()) {
            phone = getActiveFgCall(subscription).getPhone();
        } else {
            // If BG call is idle, we return default phone
            phone = getFirstActiveBgCall(subscription).getPhone();
        }
        return phone;
    }

    @Override
    public void setActiveSubscription(int subscription) {
        Log.d(LOG_TAG, "setActiveSubscription existing:" + mActiveSub + "new = " + subscription);
        mActiveSub = subscription;
        mActiveSubChangeRegistrants.notifyRegistrants(new AsyncResult (null, mActiveSub, null));
    }

    @Override
    public int getActiveSubscription() {
        if (DBG) Log.d(LOG_TAG, "getActiveSubscription  = " + mActiveSub);
        return mActiveSub;
    }

    // Update the local call hold state
    // 1 -- if call on local hold, 0 -- if call is not on local hold
    private void updateLchStatus(int sub) {
        int lchStatus = 0;
        Phone offHookPhone = getFgPhone(sub);
        Call call = offHookPhone.getForegroundCall();

        if (getActiveFgCallState(sub) == Call.State.IDLE) {
            // There is no active Fg calls, the OFFHOOK state
            // is set by the Bg call. So set the phone to bgPhone.
            offHookPhone = getBgPhone(sub);
            call = offHookPhone.getBackgroundCall();
        }
        Call.State state = call.getState();

        if (((state == Call.State.ACTIVE) || (state == Call.State.DIALING)
                || (state == Call.State.ALERTING)) && (sub != getActiveSubscription())) {
            // if sub is not an active sub and if it has an active
            // voice call then update lchStatus as 1
            lchStatus = 1;
        }
        // Update state only if the new state is different
        if (lchStatus != mLchStatus[sub]) {
            Log.d(LOG_TAG, " setLocal Call Hold to  = " + lchStatus);
            PhoneBase basePhone = (PhoneBase)getPhoneBase(offHookPhone);
            RIL rilCi = (RIL)basePhone.mCM;

            rilCi.setLocalCallHold(lchStatus, mHandler.obtainMessage(EVENT_LOCAL_CALL_HOLD));
            mLchStatus[sub] = lchStatus;
        }
    }

    private void setAudioStateParam(long vsid, int state, AudioManager audioManager) {
        String keyValPairs = new String(AudioManager.VSID_KEY + "=" + vsid + ";"
                + AudioManager.CALL_STATE_KEY + "=" + state);

        Log.d(LOG_TAG, "Setting audio state to: " + keyValPairs);

        audioManager.setParameters(keyValPairs);
    }

    private void setAudioParameters(int sub, AudioManager audioManager) {
        long vsid = 0;
        Phone offHookPhone = getFgPhone(sub);
        int newCallState = AudioManager.CALL_INACTIVE;
        int callState = AudioManager.CALL_INACTIVE;
        Call call = offHookPhone.getForegroundCall();

        if (getActiveFgCallState(sub) == Call.State.IDLE) {
            // There is no active Fg calls, the OFFHOOK state
            // is set by the Bg call. So set the phone to bgPhone.
            offHookPhone = getBgPhone(sub);
            call = offHookPhone.getBackgroundCall();
        }

        Call.State state = call.getState();

        if ((state == Call.State.ACTIVE) || (state == Call.State.DIALING)
                || (state == Call.State.ALERTING)) {
            if (sub == getActiveSubscription()) {
                newCallState = AudioManager.CALL_ACTIVE;
            } else {
                newCallState = AudioManager.CALL_LOCAL_HOLD;
            }
        } else if (state == Call.State.HOLDING) {
            newCallState = AudioManager.CALL_LOCAL_HOLD;
        } else {
            newCallState = AudioManager.CALL_INACTIVE;
        }
        Log.d(LOG_TAG, "setAudioParameters old state="+ callState + " new state=" + newCallState);

        switch(offHookPhone.getPhoneType()) {
            case PhoneConstants.PHONE_TYPE_SIP:
                //TODO VSID type for SIP phone ? and callStat for SIP phone ??
                //vsid = ??;
                Log.d(LOG_TAG, "setAudioParameters for SIP");
                break;
            case PhoneConstants.PHONE_TYPE_IMS:
                vsid = AudioManager.IMS_VSID;
                Log.d(LOG_TAG, "setAudioParameters for IMS");
                break;
            default:
                // TODO VSID support for SGLTE ?
                if (vsidVoice[sub] == -1) {
                    vsidVoice[sub] = vsidVoiceDef[sub];
                }
                vsid = vsidVoice[sub];
                break;
        }
        setAudioStateParam(vsid, newCallState, audioManager);
    }

    @Override
    public void setAudioMode() {
        if (VDBG) Log.d(LOG_TAG, "in setAudioMode State = " + getState());
        Context context = getContext();
        if (context == null) return;
        AudioManager audioManager = (AudioManager)
                context.getSystemService(Context.AUDIO_SERVICE);

        int mode = AudioManager.MODE_NORMAL;
        switch (getState()) {
            case RINGING:
                if (VDBG) Log.d(LOG_TAG, "setAudioMode RINGING");
                if (audioManager.getMode() != AudioManager.MODE_RINGTONE) {
                    // only request audio focus if the ringtone is going to be heard
                    if (audioManager.getStreamVolume(AudioManager.STREAM_RING) > 0) {
                        Log.d(LOG_TAG, "requestAudioFocus on STREAM_RING");
                        audioManager.requestAudioFocusForCall(AudioManager.STREAM_RING,
                                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
                    }
                    audioManager.setMode(AudioManager.MODE_RINGTONE);
                    if (DBG) Log.d(LOG_TAG, "setAudioMode RINGING");
                }
                break;
            case OFFHOOK:
                for (int sub = 0; sub < MSimTelephonyManager.getDefault().getPhoneCount(); sub++) {
                    // First put the calls on other SUB in LCH.
                    // In IMS, SGLTE and Single standby cases this will become false.
                    if (getActiveSubscription() != sub)
                        setAudioParameters(sub, audioManager);
                    updateLchStatus(sub);
                }
                setAudioParameters(getActiveSubscription(), audioManager);

                Phone offHookPhone = getFgPhone();
                int newAudioMode = AudioManager.MODE_IN_CALL;

                //We dont support SIP Combination in context of DSDA as of now.
                if (offHookPhone instanceof SipPhone) {
                    // enable IN_COMMUNICATION audio mode instead for sipPhone
                    Log.d(LOG_TAG, "setAudioMode Set audio mode for SIP call!");
                    newAudioMode = AudioManager.MODE_IN_COMMUNICATION;
                }

                if (VDBG) Log.d(LOG_TAG, "setAudioMode OFFHOOK mode=" + newAudioMode);
                //Need to discuss with Linux audio on getMode per sub capability?
                int currMode = audioManager.getMode();
                if (currMode != newAudioMode) {
                    // request audio focus before setting the new mode
                    audioManager.requestAudioFocusForCall(AudioManager.STREAM_VOICE_CALL,
                           AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
                    Log.d(LOG_TAG, "setAudioMode Setting audio mode from "
                            + currMode + " to " + newAudioMode);
                    audioManager.setMode(newAudioMode);
                }
                break;
            case IDLE:
                if (VDBG) Log.d(LOG_TAG, "in setAudioMode before setmode IDLE");
                if (audioManager.getMode() != AudioManager.MODE_NORMAL) {
                    setAudioParameters(getActiveSubscription(), audioManager);
                    for (int sub = 0; sub < MSimTelephonyManager.getDefault().getPhoneCount();
                            sub++) {
                        if (getActiveSubscription() != sub)
                            setAudioParameters(sub, audioManager);
                        updateLchStatus(sub);
                    }

                    audioManager.setMode(AudioManager.MODE_NORMAL);
                    Log.d(LOG_TAG, "abandonAudioFocus");
                    // abandon audio focus after the mode has been set back to normal
                    audioManager.abandonAudioFocusForCall();
                }
                break;
        }
        Log.d(LOG_TAG, "setAudioMode State = " + getState());
    }

    /**
     * Check whether any other sub is in active state other than
     * provided subscription, if yes return the other active sub.
     * @return subscription which is active.
     */
    public int getOtherActiveSub(int subscription) {
        int otherSub = -1;
        int count = MSimTelephonyManager.getDefault().getPhoneCount();

        Log.d(LOG_TAG, "is other sub active = " + subscription + count);
        for (int i = 0; i < count; i++) {
            Log.d(LOG_TAG, "Count ** " + i);
            if ((i != subscription) && (getState(i) != PhoneConstants.State.IDLE)) {
                Log.d(LOG_TAG, "got other active sub  = " + i );
                otherSub = i;
                break;
            }
        }
        return otherSub;
    }

    @Override
    public void switchToLocalHold(int subscription, boolean switchTo) {
        Phone activePhone = null;
        Phone heldPhone = null;

        Log.d(LOG_TAG, " switchToLocalHold update audio state");
        setAudioMode();

        //TODO Inform LCH sub to modem
    }

    /**
     * Whether or not the phone can conference in the current phone
     * state--that is, one call holding and one call active.
     * This method consider the phone object which is specific
     * to the provided subscription.
     * @return true if the phone can conference; false otherwise.
     */
    public boolean canConference(Call heldCall, int subscription) {
        Phone activePhone = null;
        Phone heldPhone = null;

        if (hasActiveFgCall(subscription)) {
            activePhone = getActiveFgCall(subscription).getPhone();
        }

        if (heldCall != null) {
            heldPhone = heldCall.getPhone();
        }

        return heldPhone.getClass().equals(activePhone.getClass());
    }

    /**
     * Conferences holding and active. Conference occurs asynchronously
     * and may fail. Final notification occurs via
     * {@link #registerForPreciseCallStateChanged(android.os.Handler, int,
     * java.lang.Object) registerForPreciseCallStateChanged()}.
     *
     * @exception CallStateException if canConference() would return false.
     * In these cases, this operation may not be performed.
     */
    @Override
    public void conference(Call heldCall) throws CallStateException {
        int subscription = heldCall.getPhone().getSubscription();

        if (VDBG) {
            Log.d(LOG_TAG, "conference(" +heldCall + ")");
            Log.d(LOG_TAG, this.toString());
        }

        Phone fgPhone = getFgPhone(subscription);
        if (fgPhone instanceof SipPhone) {
            ((SipPhone) fgPhone).conference(heldCall);
        } else if (canConference(heldCall)) {
            fgPhone.conference();
        } else {
            throw(new CallStateException
                    ("Can't conference foreground and selected background call"));
        }

        if (VDBG) {
            Log.d(LOG_TAG, "End conference(" +heldCall + ")");
            Log.d(LOG_TAG, this.toString());
        }
    }

    /**
     * Initiate a new voice connection. This happens asynchronously, so you
     * cannot assume the audio path is connected (or a call index has been
     * assigned) until PhoneStateChanged notification has occurred.
     *
     * @exception CallStateException if a new outgoing call is not currently
     * possible because no more call slots exist or a call exists that is
     * dialing, alerting, ringing, or waiting.  Other errors are
     * handled asynchronously.
     */
    @Override
    public Connection dial(Phone phone, String dialString, int callType, String[] extras)
            throws CallStateException {
        Phone basePhone = getPhoneBase(phone);
        Connection result;

        if (VDBG) {
            Log.d(LOG_TAG, " dial(" + basePhone + ", "+ dialString + ")");
            Log.d(LOG_TAG, this.toString());
        }

        if ( hasActiveFgCall() ) {
            Phone activePhone = getActiveFgCall().getPhone();
            boolean hasBgCall = !(activePhone.getBackgroundCall().isIdle());

            if (DBG) {
                Log.d(LOG_TAG, "hasBgCall: "+ hasBgCall + " sameChannel:"
                        + (activePhone == basePhone));
            }

            //TODO If a call on current sub is put on LCT,
            // retrieve the call from LCH

            if (activePhone != basePhone) {
                if (hasBgCall) {
                    Log.d(LOG_TAG, "Hangup");
                    getActiveFgCall().hangup();
                } else {
                    Log.d(LOG_TAG, "Switch");
                    activePhone.switchHoldingAndActive();
                }
            }
        }

        if (phone.getPhoneType() == PhoneConstants.PHONE_TYPE_IMS) {
            result = basePhone.dial(dialString, callType, extras);
        } else {
            result = basePhone.dial(dialString);
        }

        if (VDBG) {
            Log.d(LOG_TAG, "End dial(" + basePhone + ", "+ dialString + ")");
            Log.d(LOG_TAG, this.toString());
        }
        return result;
    }

    @Override
    public void clearDisconnected() {
        clearDisconnected(getActiveSubscription());
    }

    /**
     * clear disconnect connection for a phone specific
     * to the provided subscription
     */
    @Override
    public void clearDisconnected(int subscription) {
        for(Phone phone : mPhones) {
            if (phone.getSubscription() == subscription) {
                phone.clearDisconnected();
            }
        }
    }

    /**
     * Whether or not the phone specific to subscription can do explicit call transfer
     * in the current phone state--that is, one call holding and one call active.
     * @return true if the phone can do explicit call transfer; false otherwise.
     */
    public boolean canTransfer(Call heldCall, int subscription) {
        Phone activePhone = null;
        Phone heldPhone = null;

        if (hasActiveFgCall(subscription)) {
            activePhone = getActiveFgCall(subscription).getPhone();
        }

        if (heldCall != null) {
            heldPhone = heldCall.getPhone();
        }

        return (heldPhone == activePhone && activePhone.canTransfer());
    }

    @Override
    protected void handleEventVoiceSysId(Message msg) {
        AsyncResult ar;
        int sub = -1;

        ar = (AsyncResult)msg.obj;
        sub = (Integer)(ar.userObj);
        if (ar.result != null && sub != -1) {
            vsidVoice[sub] = ((Integer) (ar.result)).longValue();
            Log.d(LOG_TAG, "Voice System ID:" + vsidVoice[sub]);
        }
    }

    /**
     * Return true if there is at least one active foreground call
     * on active subscription or an active sip call
     */
    @Override
    public boolean hasActiveFgCall() {
        return hasActiveFgCall(getActiveSubscription());
    }

    /**
     * Return true if there is at least one active foreground call
     * on a particular subscription or an active sip call
     */
    @Override
    public boolean hasActiveFgCall(int subscription) {
        return (getFirstActiveCall(mForegroundCalls, subscription) != null);
    }

    /**
     * Return true if there is at least one active foreground call
     */
    @Override
    public boolean hasActiveFgCallAnyPhone() {
        return super.hasActiveFgCall();
    }

    /**
     * Return true if there is at least one active background call
     * on a active subscription or an active sip call
     */
    @Override
    public boolean hasActiveBgCall() {
        return hasActiveBgCall(getActiveSubscription());
    }

    /**
     * Return true if there is at least one active background call
     * on a particular subscription or an active sip call
     */
    @Override
    public boolean hasActiveBgCall(int subscription) {
        // TODO since hasActiveBgCall may get called often
        // better to cache it to improve performance
        return (getFirstActiveCall(mBackgroundCalls, subscription) != null);
    }

    /**
     * Return true if there is at least one active ringing call
     * on a active subscription or an active sip call
     *
     */
    @Override
    public boolean hasActiveRingingCall() {
        return hasActiveRingingCall(getActiveSubscription());
    }

    /**
     * Return true if there is at least one active ringing call
     *
     */
    @Override
    public boolean hasActiveRingingCall(int subscription) {
        return (getFirstActiveCall(mRingingCalls, subscription) != null);
    }

    /**
     * return the active foreground call from foreground calls
     *
     * Active call means the call is NOT in Call.State.IDLE
     *
     * 1. If there is active foreground call, return it
     * 2. If there is no phone registered at all, return null.
     *
     */
    @Override
    public Call getActiveFgCall() {
        return getActiveFgCall(getActiveSubscription());
    }

    @Override
    public Call getActiveFgCall(int subscription) {
        Call call = getFirstNonIdleCall(mForegroundCalls, subscription);
        if (call == null) {
            Phone phone = getPhone(subscription);
            call = (phone == null)
                    ? null
                    : phone.getForegroundCall();
        }
        return call;
    }

    // Returns the first call that is not in IDLE state. If both active calls
    // and disconnecting/disconnected calls exist, return the first active call.
    private Call getFirstNonIdleCall(List<Call> calls, int subscription) {
        Call result = null;
        for (Call call : calls) {
            if ((call.getPhone().getSubscription() == subscription) ||
                    (call.getPhone() instanceof SipPhone)) {
                if (!call.isIdle()) {
                    return call;
                } else if (call.getState() != Call.State.IDLE) {
                    if (result == null) result = call;
                }
            }
        }
        return result;
    }

    /**
     * return one active background call from background calls
     *
     * Active call means the call is NOT idle defined by Call.isIdle()
     *
     * 1. If there is only one active background call on active sub or
     *    on SIP Phone, return it
     * 2. If there is more than one active background call, return the background call
     *    associated with the active sub.
     * 3. If there is no background call at all, return null.
     *
     * Complete background calls list can be get by getBackgroundCalls()
     */
    @Override
    public Call getFirstActiveBgCall() {
        return getFirstActiveBgCall(getActiveSubscription());
    }

    /**
     * return one active background call from background calls of the
     * requested subscription.
     *
     * Active call means the call is NOT idle defined by Call.isIdle()
     *
     * 1. If there is only one active background call on given sub or
     *    on SIP Phone, return it
     * 2. If there is more than one active background call, return the background call
     *    associated with the active sub.
     * 3. If there is no background call at all, return null.
     *
     * Complete background calls list can be get by getBackgroundCalls()
     */
    @Override
    public Call getFirstActiveBgCall(int subscription) {
        Phone phone = getPhone(subscription);
        if (hasMoreThanOneHoldingCall(subscription)) {
            return phone.getBackgroundCall();
        } else {
            Call call = getFirstNonIdleCall(mBackgroundCalls, subscription);
            if (call == null) {
                call = (phone == null)
                        ? null
                        : phone.getBackgroundCall();
            }
            return call;
        }
    }

    /**
     * return one active ringing call from ringing calls
     *
     * Active call means the call is NOT idle defined by Call.isIdle()
     *
     * 1. If there is only one active ringing call on active sub or
     *    SIP Phone, return it.
     * 2. If there is more than one active ringing call on active
     *    sub or on SIP Phone, return the first one
     * 3. If there is no ringing call at all, return null.
     *
     * Complete ringing calls list can be get by getRingingCalls()
     */
    @Override
    public Call getFirstActiveRingingCall() {
        return getFirstActiveRingingCall(getActiveSubscription());
    }

    @Override
    public Call getFirstActiveRingingCall(int subscription) {
        Phone phone = getPhone(subscription);
        Call call = getFirstNonIdleCall(mRingingCalls, subscription);
        if (call == null) {
            call = (phone == null)
                    ? null
                    : phone.getRingingCall();
        }
        return call;
    }

    /**
     * @return the state of active foreground call on active sub.
     * return IDLE if there is no active sub foreground call
     */
    @Override
    public Call.State getActiveFgCallState() {
        return getActiveFgCallState(getActiveSubscription());
    }

    @Override
    public Call.State getActiveFgCallState(int subscription) {
        Call fgCall = getActiveFgCall(subscription);

        if (fgCall != null) {
            return fgCall.getState();
        }

        return Call.State.IDLE;
    }

    /**
     * @return the connections of active foreground call
     * return empty list if there is no active foreground call
     */
    public List<Connection> getFgCallConnections(int subscription) {
        Call fgCall = getActiveFgCall(subscription);
        if ( fgCall != null) {
            return fgCall.getConnections();
        }
        return emptyConnections;
    }

    /**
     * @return the connections of active background call
     * return empty list if there is no active background call
     */
    public List<Connection> getBgCallConnections(int subscription) {
        Call bgCall = getFirstActiveBgCall(subscription);
        if ( bgCall != null) {
            return bgCall.getConnections();
        }
        return emptyConnections;
    }

    /**
     * @return the latest connection of active foreground call
     * return null if there is no active foreground call
     */
    @Override
    public Connection getFgCallLatestConnection(int subscription) {
        Call fgCall = getActiveFgCall(subscription);
        if ( fgCall != null) {
            return fgCall.getLatestConnection();
        }
        return null;
    }

    /**
     * @return true if there is at least one Foreground call in disconnected state
     */
    public boolean hasDisconnectedFgCall(int subscription) {
        return (getFirstCallOfState(mForegroundCalls, Call.State.DISCONNECTED,
                subscription) != null);
    }

    /**
     * @return true if there is at least one background call in disconnected state
     */
    public boolean hasDisconnectedBgCall(int subscription) {
        return (getFirstCallOfState(mBackgroundCalls, Call.State.DISCONNECTED,
                subscription) != null);
    }

    /**
     * @return the first active call from a call list
     */
    private  Call getFirstActiveCall(ArrayList<Call> calls, int subscription) {
        for (Call call : calls) {
            if ((!call.isIdle()) && ((call.getPhone().getSubscription() == subscription) ||
                    (call.getPhone() instanceof SipPhone))) {
                return call;
            }
        }
        return null;
    }

    /**
     * @return the first call in a the Call.state from a call list
     */
    private Call getFirstCallOfState(ArrayList<Call> calls, Call.State state,
            int subscription) {
        for (Call call : calls) {
            if ((call.getState() == state) ||
                ((call.getPhone().getSubscription() == subscription) ||
                (call.getPhone() instanceof SipPhone))) {
                return call;
            }
        }
        return null;
    }

    /**
     * @return true if more than one active ringing call exists on
     * the active subscription.
     * This checks for the active calls on provided
     * subscription and also active calls on SIP Phone.
     *
     */
    private boolean hasMoreThanOneRingingCall() {
        int subscription = getActiveSubscription();
        int count = 0;

        for (Call call : mRingingCalls) {
            if ((call.getState().isRinging()) &&
                ((call.getPhone().getSubscription() == subscription) ||
                (call.getPhone() instanceof SipPhone))) {
                if (++count > 1) return true;
            }
        }
        return false;
    }

    /**
     * @return true if more than one active background call exists on
     * the provided subscription.
     * This checks for the background calls on provided
     * subscription and also background calls on SIP Phone.
     *
     */
    private boolean hasMoreThanOneHoldingCall(int subscription) {
        int count = 0;
        for (Call call : mBackgroundCalls) {
            if ((call.getState() == Call.State.HOLDING) &&
                ((call.getPhone().getSubscription() == subscription) ||
                (call.getPhone() instanceof SipPhone))) {
                if (++count > 1) return true;
            }
        }
        return false;
    }

    @Override
    protected void handleEventRingingConnection(Message msg) {
        if (VDBG) Log.d(LOG_TAG, " handleMessage (EVENT_NEW_RINGING_CONNECTION)");
        Connection c = (Connection) ((AsyncResult) msg.obj).result;
        int sub = c.getCall().getPhone().getSubscription();
        if (getActiveFgCallState(sub).isDialing() || hasMoreThanOneRingingCall()) {
            try {
                Log.d(LOG_TAG, "silently drop incoming call: " + c.getCall());
                c.getCall().hangup();
            } catch (CallStateException e) {
                Log.w(LOG_TAG, "new ringing connection", e);
            }
        } else {
            mNewRingingConnectionRegistrants.notifyRegistrants((AsyncResult) msg.obj);
        }
    }

    @Override
    public String toString() {
        Call call;
        StringBuilder b = new StringBuilder();

        b.append("ExtCallManager {");
        for (int i = 0; i < MSimTelephonyManager.getDefault().getPhoneCount(); i++) {
            b.append("\nSUB"+i);
            b.append("\nstate = " + getState(i));
            call = getActiveFgCall(i);
            b.append("\n- Foreground: " + getActiveFgCallState(i));
            b.append(" from " + call.getPhone());
            b.append("\n  Conn: ").append(getFgCallConnections(i));
            call = getFirstActiveBgCall(i);
            b.append("\n- Background: " + call.getState());
            b.append(" from " + call.getPhone());
            b.append("\n  Conn: ").append(getBgCallConnections(i));
            call = getFirstActiveRingingCall(i);
            b.append("\n- Ringing: " +call.getState());
            b.append(" from " + call.getPhone());
        }

        for (Phone phone : getAllPhones()) {
            if (phone != null) {
                b.append("\nPhone: " + phone + ", name = " + phone.getPhoneName()
                        + ", state = " + phone.getState());
                call = phone.getForegroundCall();
                b.append("\n- Foreground: ").append(call);
                call = phone.getBackgroundCall();
                b.append(" Background: ").append(call);
                call = phone.getRingingCall();
                b.append(" Ringing: ").append(call);
            }
        }
        b.append("\n}");
        return b.toString();
    }
}
