package com.life0423.carddock.platform.mika;

import android.view.Surface;

// 只操作助手自有的屏，不提供通用 shell 或主屏点击。
interface ICardDockDisplay {
    int openCainiao(in Surface surface, int width, int height, int density);
    int openMeituan(in Surface surface, int width, int height, int density, String oid);
    void closeDisplay(int displayId);
    int openMeituanOrders(in Surface surface, int width, int height, int density);
    int openLauncher(in Surface surface, int width, int height, int density, String targetPackage);
    int openIndependentLauncher(in Surface surface, int width, int height, int density, String targetPackage);
    // 仅交接自有查询屏上已核对的菜鸟取件任务。试运行完成后可移回原屏。
    boolean movePickupList(int displayId, boolean returnToQuery);
    oneway void stopHelper();
    // 仅允许交接查询屏上的麦当劳官方套餐详情。
    boolean showMealDetail(int displayId);
    int openBookShare(in Surface surface, int width, int height, int density, String url);
}
