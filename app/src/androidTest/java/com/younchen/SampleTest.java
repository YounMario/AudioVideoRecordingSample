package com.younchen;

import android.content.Context;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Created by 龙泉 on 2016/12/5.
 */

@RunWith(AndroidJUnit4.class)
public class SampleTest {

    @Test
    public void testSample(){
        Context appContext = InstrumentationRegistry.getTargetContext();
        System.out.println("appName:" + appContext.getPackageName());
    }
}
