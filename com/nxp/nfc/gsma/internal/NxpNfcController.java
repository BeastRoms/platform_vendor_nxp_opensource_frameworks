/*
 * Copyright (c) 2015-2016, The Linux Foundation. All rights reserved.
 * Not a Contribution.
 *
 * Copyright (C) 2018 NXP Semiconductors
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
package com.nxp.nfc.gsma.internal;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.ApplicationInfo;
import android.graphics.drawable.Drawable;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Arrays;
import java.util.Random;
import android.nfc.NfcAdapter;
import com.nxp.nfc.NxpNfcAdapter;
import android.annotation.SystemApi;
import android.util.Log;
import android.nfc.cardemulation.NfcAidGroup;
import android.nfc.cardemulation.AidGroup;
import android.nfc.cardemulation.NfcApduServiceInfo;
import android.nfc.cardemulation.NfcApduServiceInfo.ESeInfo;
import android.os.UserHandle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import com.nxp.nfc.gsma.internal.INxpNfcController;
import com.nxp.nfc.NfcConstants;
import java.io.ByteArrayOutputStream;

public class NxpNfcController {

    public static final int TECHNOLOGY_NFC_A=0x01;
    public static final int TECHNOLOGY_NFC_B=0x02;
    public static final int TECHNOLOGY_NFC_F=0x04;
    public static final int PROTOCOL_ISO_DEP=0x10;
    private static final int MW_PROTOCOL_MASK_ISO_DEP = 0x08;

    static final String TAG = "NxpNfcController";
    static final Boolean DBG = true;

    /** Battery of the handset is in "Operational" mode*/
    public static final int BATTERY_OPERATIONAL_STATE=0x01;
    /** Any battery power levels*/
    public static final int BATTERY_ALL_STATES=0x02;

    /** Screen is "ON" (not in "Screen Off" mode) and locked*/
    public static final int SCREEN_ON_AND_LOCKED_MODE=0x01;
    /** Any screen mode*/
    public static final int SCREEN_ALL_MODES=0x02;

    Context mContext;
    private NfcAdapter mNfcAdapter = null;
    private NxpNfcAdapter mNxpNfcAdapter = null;
    private INxpNfcController mNfcControllerService = null;
    private ESeInfo seExtension;
    private boolean mEnable = false;
    private boolean mState = false;
    private boolean mDialogBoxFlag = false;
    private NxpNfcController.NxpCallbacks mCallBack = null;

    // Map between SE name and NfcApduServiceInfo
    private final HashMap<String, NfcApduServiceInfo> mSeNameApduService =  new HashMap<String, NfcApduServiceInfo>();//Maps.newHashMap();

    public static interface NxpCallbacks {
        /**
         * Called when process for enabling the NFC Controller is finished.
         */
        public abstract void onNxpEnableNfcController(boolean success);

    }

    // For QC
    public static interface Callbacks {
        /**
         * Called when process for enabling the NFC Controller is finished.
         * @hide
         */
        public void onGetOffHostService(boolean isLast, String description, String seName, int bannerResId,
                                         List<String> dynamicAidGroupDescriptions,
                                         List<android.nfc.cardemulation.AidGroup> dynamicAidGroups);

    }

    private final BroadcastReceiver mOwnerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            int state = intent.getIntExtra(NfcAdapter.EXTRA_ADAPTER_STATE,0);
            Log.d(TAG,"onReceive: action: " + action + "mState: "+ mState);
            if((state == NfcAdapter.STATE_ON)  && (mState == true) && (mDialogBoxFlag == true)) {
                mEnable = true;
                mCallBack.onNxpEnableNfcController(true);
                mDialogBoxFlag = false;
                mState = false;
                mContext.unregisterReceiver(mOwnerReceiver);
                mContext.unregisterReceiver(mReceiver);
            }
        }
    };

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if(action.equals(NfcConstants.ACTION_GSMA_ENABLE_SET_FLAG)) {
                mState = intent.getExtras().getBoolean("ENABLE_STATE");
            }
            if(mState == false) {
                mCallBack.onNxpEnableNfcController(false);
                mContext.unregisterReceiver(mOwnerReceiver);
                mContext.unregisterReceiver(mReceiver);
            } else {
                mDialogBoxFlag = true;
            }
        }
    };

    public NxpNfcController() {}

    public NxpNfcController(Context context) {
        mContext = context;
        if(mNfcAdapter == null)
            mNfcAdapter = NfcAdapter.getNfcAdapter(mContext);
        if((mNxpNfcAdapter == null) && (mNfcAdapter != null))
            mNxpNfcAdapter = NxpNfcAdapter.getNxpNfcAdapter(mNfcAdapter);

        if(mNfcControllerService == null) {
            mNfcControllerService = mNxpNfcAdapter.getNxpNfcControllerInterface();
        }
    }

    /**
     * Check if the NFC Controller is enabled or disabled.
     * return true,if the NFC adapter is enabled and false otherwise
     */
    public boolean isNxpNfcEnabled() {
        return mNfcAdapter.isEnabled ();
    }

    /**
     * Asks the system to enable the NFC Controller.
     */
    public void enableNxpNfcController(NxpNfcController.NxpCallbacks cb) {

        mCallBack = cb;
        IntentFilter ownerFilter = new IntentFilter();
        ownerFilter.addAction(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED);
        mContext.registerReceiver(mOwnerReceiver, ownerFilter);

        IntentFilter filter = new IntentFilter();
        filter.addAction(NfcConstants.ACTION_GSMA_ENABLE_SET_FLAG);
        mContext.registerReceiver(mReceiver, filter);

        // To Enable NfC
        Intent enableNfc = new Intent();
        enableNfc.setAction(NfcConstants.ACTION_GSMA_ENABLE_NFC);
        mContext.sendBroadcast(enableNfc);
    }

    /**
     * Converting from apdu service to off host service.
     * return Off-Host service object
     */
    private NxpOffHostService ConvertApduServiceToOffHostService(PackageManager pm, NfcApduServiceInfo apduService) {
        NxpOffHostService mService;
        int seId=0;
        String sEname =null;
        ResolveInfo resolveInfo = apduService.getResolveInfo();
        String description = apduService.getDescription();
        seId = apduService.getSEInfo().getSeId();
        if (NfcConstants.UICC_ID_TYPE == seId) {
            sEname = "SIM1";
        } else if (NfcConstants.UICC2_ID_TYPE == seId) {
            sEname = "SIM2";
        } else if (NfcConstants.SMART_MX_ID_TYPE == seId) {
            sEname = "eSE";
        } else {
            Log.e(TAG,"Wrong SE ID");
        }
        Drawable banner = null; //apduService.loadBanner(pm);
        boolean modifiable = apduService.getModifiable();
        int bannerId = apduService.getBannerId();
        banner = apduService.loadBanner(pm);
        int userId = apduService.getUid();
        ArrayList<String> ApduAids = apduService.getAids();
        mService =  new NxpOffHostService(userId,description, sEname, resolveInfo.serviceInfo.packageName,
                                          resolveInfo.serviceInfo.name, modifiable);
        if(modifiable) {
            for(android.nfc.cardemulation.NfcAidGroup group : apduService.getDynamicNfcAidGroups()) {
                mService.mNfcAidGroupList.add(group);
            }
        } else {
            for(android.nfc.cardemulation.NfcAidGroup group : apduService.getStaticNfcAidGroups()) {
                mService.mNfcAidGroupList.add(group);
            }
        }
        //mService.setBanner(banner);
        mService.setContext(mContext);
        mService.setBannerId(bannerId);
        mService.setBanner(banner);
        mService.setNxpNfcController(this);
        return mService;
    }

    /**
     * Converting from Off_Host service object to Apdu Service object
     * return APDU service Object
     */
    private NfcApduServiceInfo ConvertOffhostServiceToApduService(NxpOffHostService mService, int userId, String pkg) {
        NfcApduServiceInfo apduService = null;
        boolean onHost = false;
        String description = mService.getDescription();
        boolean modifiable = mService.getModifiable();
        ArrayList<android.nfc.cardemulation.NfcAidGroup> staticNfcAidGroups = new ArrayList<NfcAidGroup>();
        ArrayList<NfcAidGroup> dynamicNfcAidGroup = new ArrayList<NfcAidGroup>();
        dynamicNfcAidGroup.addAll(mService.mNfcAidGroupList);
        boolean requiresUnlock = false;
        Drawable banner = mService.getBanner();
        byte[] byteArrayBanner = null;

        if(banner != null){
            Bitmap bitmap = (Bitmap)((BitmapDrawable)banner).getBitmap();
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
            byteArrayBanner = stream.toByteArray();
        }

        int seId = 0;
        String seName = mService.getLocation();
        int powerstate = -1;
        int bannerId = mService.mBannerId;
        /* creating Resolveinfo object */
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.serviceInfo = new ServiceInfo();
        resolveInfo.serviceInfo.applicationInfo = new ApplicationInfo();
        resolveInfo.serviceInfo.packageName = pkg;
        resolveInfo.serviceInfo.name = mService.getServiceName();
        if(seName != null) {
            if(seName.equals("SIM") || seName.equals("SIM1")) {
                seId = NfcConstants.UICC_ID_TYPE;
            } else if (seName.equals("SIM2")) {
                seId = NfcConstants.UICC2_ID_TYPE;
            } else if (seName.equals("eSE")) {
                seId = NfcConstants.SMART_MX_ID_TYPE;
            } else {
                Log.e(TAG,"wrong Se name");
            }
        }
        NfcApduServiceInfo.ESeInfo mEseInfo = new NfcApduServiceInfo.ESeInfo(seId,powerstate);
        apduService = new NfcApduServiceInfo(resolveInfo,onHost,description,staticNfcAidGroups, dynamicNfcAidGroup,
                                           requiresUnlock,bannerId,userId, "Fixme: NXP:<Activity Name>", mEseInfo, modifiable);
        return apduService;
    }

    /**
     * Delete Off-Host service from routing table
     * return true or false
     */
    public boolean deleteOffHostService(int userId, String packageName, NxpOffHostService service) {
        boolean result = false;
        NfcApduServiceInfo apduService;
        apduService = ConvertOffhostServiceToApduService(service, userId, packageName);
        try {
            result = mNfcControllerService.deleteOffHostService(userId, packageName, apduService);
        } catch (RemoteException e) {
            Log.e(TAG, "Exception:deleteOffHostService failed", e);
        }
        if(result != true) {
            Log.d(TAG, "GSMA: deleteOffHostService failed");
            return false;
        }
        return true;
    }

    /**
     * Get the list Off-Host services
     * return off-Host service List
     */
    public ArrayList<NxpOffHostService> getOffHostServices(int userId, String packageName) {
        List<NfcApduServiceInfo> apduServices = new ArrayList<NfcApduServiceInfo>();
        ArrayList<NxpOffHostService> mService = new ArrayList<NxpOffHostService>();
        PackageManager pm = mContext.getPackageManager();
        try {
            apduServices = mNfcControllerService.getOffHostServices(userId, packageName);
            if((apduServices == null)||(apduServices.isEmpty())){
                return null;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "getOffHostServices failed", e);
            return null;
        }
        for(NfcApduServiceInfo service: apduServices) {
            mService.add(ConvertApduServiceToOffHostService(pm, service));
        }
        return mService;
   }

    /**
     * Get the Default Off-Host services
     * return default off-Host service
     */
    public NxpOffHostService getDefaultOffHostService(int userId, String packageName) {
        NfcApduServiceInfo apduService;
        NxpOffHostService mService;
        PackageManager pm = mContext.getPackageManager();
        try {
            apduService = mNfcControllerService.getDefaultOffHostService(userId, packageName);
        } catch (RemoteException e) {
            Log.e(TAG, "getDefaultOffHostService failed", e);
            return null;
        }
        if(apduService != null) {
            mService = ConvertApduServiceToOffHostService(pm, apduService);
            return mService;
        }
        Log.e(TAG, "getDefaultOffHostService: Service is NULL");
        return null;
    }

    /**
     * add the Off-Host service to routing tableh
     * return true
     */
    public boolean commitOffHostService(int userId, String packageName, NxpOffHostService service) {
        boolean result = false;
        NfcApduServiceInfo newService;
        String serviceName = service.getServiceName();
        newService = ConvertOffhostServiceToApduService(service, userId, packageName);
        try {
            if(mNfcControllerService != null) {
                result = mNfcControllerService.commitOffHostService(userId, packageName, serviceName, newService);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Exception:commitOffHostService failed", e);
            result = false;
        }
        if(result != true) {
            Log.e(TAG, "GSMA: commitOffHostService Failed");
        }
        return result;
    }


    /**
     * add the Off-Host service to routing tableh
     * return true
     * @hide
     */
    public boolean commitOffHostService(String packageName, String seName, String description,
                                        int bannerResId, int uid, List<String> aidGroupDescriptions,
                                        List<android.nfc.cardemulation.NfcAidGroup> nfcAidGroups) {

        boolean result = false;
        int userId = UserHandle.myUserId();
        NfcApduServiceInfo service = null;
        boolean onHost = false;
        ArrayList<android.nfc.cardemulation.NfcAidGroup> staticNfcAidGroups = new ArrayList<NfcAidGroup>();
        ArrayList<NfcAidGroup> dynamicNfcAidGroup = new ArrayList<NfcAidGroup>();
        dynamicNfcAidGroup.addAll(nfcAidGroups);
        boolean requiresUnlock = false;
        int seId = 0;
        int powerstate = -1;
        boolean modifiable = true;

        /* creating Resolveinfo object */
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.serviceInfo = new ServiceInfo();
        resolveInfo.serviceInfo.applicationInfo = new ApplicationInfo();
        resolveInfo.serviceInfo.packageName = packageName;
        resolveInfo.serviceInfo.name = seName;

        //Temp for SE conversion
        String secureElement = null;
        if((seName.equals("SIM")) || (seName.equals("SIM1"))) {
            secureElement = NfcConstants.UICC_ID;
        } else if (seName.equals("SIM2")){
            secureElement = NfcConstants.UICC2_ID;
        } else if ((seName.equals("eSE1")) || (seName.equals("eSE"))){
            secureElement = NfcConstants.SMART_MX_ID;
        } else {
            Log.e(TAG,"wrong Se name");
        }

        if(secureElement.equals(NfcConstants.UICC_ID)) {
            seId = NfcConstants.UICC_ID_TYPE;
        } else if (secureElement.equals(NfcConstants.UICC2_ID)) {
            seId = NfcConstants.UICC2_ID_TYPE;
        } else if (secureElement.equals(NfcConstants.SMART_MX_ID)) {
            seId = NfcConstants.SMART_MX_ID_TYPE;
        } else if (secureElement.equals(NfcConstants.HOST_ID)) {
            seId = NfcConstants.HOST_ID_TYPE;
        } else {
            Log.e(TAG,"wrong Se name");
        }

        NfcApduServiceInfo.ESeInfo mEseInfo = new NfcApduServiceInfo.ESeInfo(seId,powerstate);
        NfcApduServiceInfo newService = new NfcApduServiceInfo(resolveInfo, onHost, description, staticNfcAidGroups, dynamicNfcAidGroup,
                                                         requiresUnlock, bannerResId, userId, "Fixme: NXP:<Activity Name>", mEseInfo,
                                                         modifiable);

        mSeNameApduService.put(seName, newService);

        try {
            if(mNfcControllerService != null) {
                result = mNfcControllerService.commitOffHostService(userId, packageName, seName, newService);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Exception:commitOffHostService failed " + e.getMessage());
            result = false;
        }
        if(result != true) {
            Log.w(TAG, "GSMA: commitOffHostService Failed");
        }
        return result;
    }

    /**
     * Delete Off-Host service from routing table
     * return true or false
     */
    public boolean deleteOffHostService(String packageName, String seName) {

        boolean result = false;
        int userId = UserHandle.myUserId();

        try {
            result = mNfcControllerService.deleteOffHostService(userId, packageName, mSeNameApduService.get(seName));
        } catch (RemoteException e) {
            Log.e(TAG, "Exception:deleteOffHostService failed", e);
        }
        if(result != true) {
            Log.w(TAG, "GSMA: deleteOffHostService failed");
            return result;
        }
        return result;
    }

    /**
     * Get the list Off-Host services
     * return off-Host service List
     */
    public boolean getOffHostServices(String packageName, Callbacks callbacks) {

        int userId = UserHandle.myUserId();
        boolean isLast = false;
        String seName = null;
        int seId=0;

        List<NfcApduServiceInfo> apduServices = new ArrayList<NfcApduServiceInfo>();
        try {
            apduServices = mNfcControllerService.getOffHostServices(userId, packageName);

            for(int i =0; i< apduServices.size(); i++) {

                if( i == apduServices.size() -1 ) {
                    isLast = true;
                }
                seId = apduServices.get(i).getSEInfo().getSeId();
                if (NfcConstants.UICC_ID_TYPE == seId) {
                    seName = "SIM1";
                } else if (NfcConstants.UICC2_ID_TYPE == seId) {
                    seName = "SIM2";
                } else if (NfcConstants.SMART_MX_ID_TYPE == seId) {
                    seName = "eSE";
                } else {
                    seName = null;
                    Log.e(TAG,"Wrong SE ID");
                }

                Log.d(TAG, "getOffHostServices: seName = " + seName);
                ArrayList<String> groupDescription = new ArrayList<String>();
                for (NfcAidGroup nfcAidGroup : apduServices.get(i).getNfcAidGroups()) {
                    groupDescription.add(nfcAidGroup.getDescription());
                }

                callbacks.onGetOffHostService(isLast, apduServices.get(i).getDescription(), seName, apduServices.get(i).getBannerId(),
                                              groupDescription,
                                              apduServices.get(i).getAidGroups());
            }
        } catch (RemoteException e) {
            Log.e(TAG, "getOffHostServices failed", e);
            return false;
        }

       return true;
    }

    /**
     * Get the Default Off-Host services
     * return default off-Host service
     */
    public boolean getDefaultOffHostService(String packageName, Callbacks callbacks) {

        Log.d(TAG, "getDefaultOffHostService: Enter");

        NfcApduServiceInfo apduService;
        boolean isLast = true;
        int userId = UserHandle.myUserId();
        String seName = null;
        int seId=0;
        try {
            apduService = mNfcControllerService.getDefaultOffHostService(userId, packageName);
            seId = apduService.getSEInfo().getSeId();
            if (NfcConstants.UICC_ID_TYPE == seId) {
                seName = "SIM1";
            } else if (NfcConstants.UICC2_ID_TYPE == seId) {
                seName = "SIM2";
            } else if (NfcConstants.SMART_MX_ID_TYPE== seId) {
                seName = "eSE";
            } else {
                Log.e(TAG,"Wrong SE ID");
            }
            Log.d(TAG, "getDefaultOffHostService: seName = " + seName);
            ArrayList<String> groupDescription = new ArrayList<String>();
            for (NfcAidGroup nfcAidGroup : apduService.getNfcAidGroups()) {
                groupDescription.add(nfcAidGroup.getDescription());
            }

            callbacks.onGetOffHostService(isLast, apduService.getDescription(), seName, apduService.getBannerId(),
                                          groupDescription,
                                          apduService.getAidGroups());
        } catch (RemoteException e) {
            Log.e(TAG, "getDefaultOffHostService failed", e);
            return false;
        }

        Log.d(TAG, "getDefaultOffHostService: End");
        return true;
    }

    /**
     * To enable the the system to inform "transaction events" to any authorized/registered components
     * via BroadcastReceiver
     *
     */
    public void enableMultiReception(String seName, String packageName) {
        try {
            mNfcControllerService.enableMultiReception(packageName, seName);
        } catch (RemoteException e) {
            Log.e(TAG, "enableMultiReception failed", e);
            return;
        }
    }

    public boolean isStaticOffhostService(int userId, String packageName, NxpOffHostService service) {
        boolean isStatic = false;
        List<NfcApduServiceInfo> nqApduServices = new ArrayList<NfcApduServiceInfo>();

        try {
            nqApduServices = mNfcControllerService.getOffHostServices(userId, packageName);

            for(int i=0; i< nqApduServices.size(); i++) {
                NfcApduServiceInfo sService = nqApduServices.get(i);
                if(sService.getModifiable() == false && service.getServiceName().compareTo((sService.getResolveInfo()).serviceInfo.name)==0){
                    isStatic = true;
                }
            }
        } catch (RemoteException e) {
            Log.e(TAG, "getOffHostServices failed", e);
            isStatic = true;
        }
        return isStatic;
    }
}
