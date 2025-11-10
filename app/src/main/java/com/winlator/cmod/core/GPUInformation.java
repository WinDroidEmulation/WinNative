package com.winlator.cmod.core;

import android.content.Context;

import java.util.Locale;

public abstract class GPUInformation {

    public static boolean isAdrenoGPU(Context context) {
        return getRenderer(null, context).toLowerCase().contains("adreno");
    }
    public native static String getVersion(String driverName, Context context);
    public native static String getRenderer(String driverName, Context context);
    public native static String[] enumerateExtensions(String driverName, Context context);

    static {
        System.loadLibrary("winlator");
    }
}
