package com.dsh.pet;

/** 项目链接。仓库地址只在这一处改，界面上的文案会跟着走。 */
public final class Links {
    /** 例：https://github.com/your-name/desktop-pet ；留空时界面不显示相关行。 */
    public static final String REPO = "https://github.com/danmeyy/DesktopPet";

    private Links() {}

    public static boolean hasRepo() {
        return REPO != null && REPO.length() > 0;
    }
}
