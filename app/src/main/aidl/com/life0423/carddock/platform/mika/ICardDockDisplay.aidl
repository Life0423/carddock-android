package com.life0423.carddock.platform.mika;

import android.view.Surface;

// 只操作助手自有的屏，不提供通用 shell 或主屏点击。
interface ICardDockDisplay {
    int openCainiao(in Surface surface, int width, int height, int density);
    int openMeituan(in Surface surface, int width, int height, int density, String oid);
    void closeDisplay(int displayId);
    int openMeituanOrders(in Surface surface, int width, int height, int density);
}
