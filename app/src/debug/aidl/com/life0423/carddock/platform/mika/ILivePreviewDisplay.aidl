package com.life0423.carddock.platform.mika;

import android.view.MotionEvent;
import android.view.Surface;

// 只在明确授权的调试实验中使用。所有操作限于本会话拥有的屏。
interface ILivePreviewDisplay {
    int openPreview(in Surface surface, int width, int height, int density, String targetPackage, @nullable String url);
    boolean attachSurface(int displayId, in @nullable Surface surface);
    boolean showsTarget(int displayId);
    oneway void touch(int displayId, in MotionEvent event);
    boolean back(int displayId);
    boolean openOnMainDisplay(int displayId);
    void closePreview(int displayId);
    oneway void stopHelper();
}
