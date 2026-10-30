package com.geesatcom.test;

import com.geesatcom.manager.BleManager;
import com.geesatcom.protocollibrary.controller.DispatchManager;
import com.geesatcom.protocollibrary.controller.ModuleDataManager;
import com.geesatcom.protocollibrary.interfaces.ModuleOperation;
import com.geesatcom.toolkit.LogUtils;

public final class CommunicationManager implements ModuleOperation {

    public final DispatchManager dispatchManager;

    public final ModuleDataManager moduleDataManager;


    private static class SingletonHelper {
        private static final CommunicationManager INSTANCE = new CommunicationManager();
    }

    public static CommunicationManager getInstance() {
        return SingletonHelper.INSTANCE;
    }


    private CommunicationManager() {
        this.dispatchManager = new DispatchManager(this);
        this.moduleDataManager = dispatchManager.getDataManager();
    }



    @Override
    public void initialization(boolean isReady) {
        LogUtils.d("wuhao", "isReady: " + isReady);
    }

    @Override
    public void passthrough(String pactWrap) {

    }

    @Override
    public void send(byte[] data) {
        BleManager.getInstance().sendData(data);
    }

    @Override
    public void powerOn() {
    }

    @Override
    public void powerDown() {
    }
}
