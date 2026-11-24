package com.wangzan101.mika.cap.phone.a11y;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** 把测试用的账号认证器交给系统(见 {@link TestAuthenticator})。 */
public class TestAuthenticatorService extends Service {
    private TestAuthenticator authenticator;

    @Override
    public void onCreate() {
        super.onCreate();
        authenticator = new TestAuthenticator(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return authenticator.getIBinder();
    }
}
