/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.server.am.apm;

import android.util.ArraySet;

/**
 * Games the ColorOS image ships as {@code appType=4} but does not ask the package
 * manager about.
 *
 * <p>{@link android.content.pm.ApplicationInfo#category} is the authoritative answer and
 * is what {@link NetworkFreezeController} asks first. The list below is the second
 * answer, taken verbatim from {@code sys_hans_hardcoded_app_type_list.xml} on the
 * target image (version {@code 2026072801}, the {@code typeCode="4"} rows). Many
 * Chinese games ship without {@code android:isGame}, so the category alone would give
 * them no grace at all and they would reconnect on every freeze.
 *
 * <p>It is a snapshot of one image, not a rule. Entries are dropped when the image drops
 * them; nothing outside this list is inferred, and a package the list mentions is only
 * ever given the game grace, never a different protection.
 */
final class ApmGamePackages {
    private static final String[] PACKAGES = {
            "cn.jj",
            "com.ChillyRoom.nearme.gamecenter",
            "com.PigeonGames.Phigros",
            "com.RoamingStar.BlueArchive",
            "com.bairimeng.dmmdzz.nearme.gamecenter",
            "com.bf.sgs.hdexp",
            "com.bianxingjss.nearme.gamecenter",
            "com.bilibili.azurlane",
            "com.blockchainvault",
            "com.boyaa.unionmj.oppo.nearme.gamecenter",
            "com.cmge.dz.xyermj.nearme.gamecenter",
            "com.cmge.oppo.nearme.gamecenter",
            "com.feiyu.luobo4.nearme.gamecenter",
            "com.guandan.nearme.gamecenter",
            "com.happyelements.AndroidAnimal",
            "com.happyelements.AndroidAnimal.qq",
            "com.heytap.xgame",
            "com.hortor.juliancysj",
            "com.hottagames.yh.laohu",
            "com.huluxia.gametools",
            "com.hupu.games",
            "com.hypergryph.arknights",
            "com.jd.game.block1010.nearme.gamecenter",
            "com.jd.game.fruitsmasher.nearme.gamecenter",
            "com.je.skgame.nearme.gamecenter",
            "com.joym.legendhero.nearme.gamecenter",
            "com.jym.gcmall",
            "com.k7k7.BaoHuang.nearme.gamecenter",
            "com.k7k7.goujihd.nearme.gamecenter",
            "com.k7k7.shengjihd.nearme.gamecenter",
            "com.kiloo.subwaysurf",
            "com.kurogame.haru.hero",
            "com.kurogame.mingchao",
            "com.lanse.chinachess.nearme.gamecenter",
            "com.ledou.mhhy.nearme.gamecenter",
            "com.lew.game.pianoblock.nearme.gamecenter",
            "com.linzihy.mgddz.nearme.gamecenter",
            "com.ls.ndndhd.nearme.gamecenter",
            "com.maj3D.qmmj.nearme.gamecenter",
            "com.mfp.jelly.oppo",
            "com.mgss.mihuan.nearme.gamecenter",
            "com.miHoYo.Nap",
            "com.miHoYo.Yuanshen",
            "com.miHoYo.bh3.nearme.gamecenter",
            "com.miHoYo.cloudgames.ys",
            "com.miHoYo.enterprise.NGHSoD",
            "com.miHoYo.hkrpg",
            "com.mihoyo.hyperion",
            "com.minitech.miniworld.nearme.gamecenter",
            "com.miniworldroyale.nearme.gamecenter",
            "com.ms.miga.world.nearme.gamecenter",
            "com.netease.aceracer.nearme.gamecenter",
            "com.netease.allstar",
            "com.netease.buff",
            "com.netease.cbg",
            "com.netease.dfjs.nearme.gamecenter",
            "com.netease.dwrg",
            "com.netease.dwrg.nearme.gamecenter",
            "com.netease.l22.nearme.gamecenter",
            "com.netease.mc.nearme.gamecenter",
            "com.netease.mhxyhtb",
            "com.netease.mkey",
            "com.netease.mrzh.nearme.gamecenter",
            "com.netease.my",
            "com.netease.nshm",
            "com.netease.nshm.nearme.gamecenter",
            "com.netease.onmyoji",
            "com.netease.party",
            "com.netease.party.aligames",
            "com.netease.party.huawei",
            "com.netease.party.kuaishou",
            "com.netease.party.nearme.gamecenter",
            "com.netease.party.vivo",
            "com.netease.pes.nearme.gamecenter",
            "com.netease.race",
            "com.netease.sjzw",
            "com.netease.sjzw.xg01",
            "com.netease.sky",
            "com.netease.sky.huawei",
            "com.netease.sky.nearme.gamecenter",
            "com.netease.stzb.netease",
            "com.netease.tom.nearme.gamecenter",
            "com.netease.xyqcbg",
            "com.netease.yhtj",
            "com.netease.yhtj.nearme.gamecenter",
            "com.oplus.games",
            "com.oplus.play",
            "com.outfit7.herodash.nearme.gamecenter",
            "com.outfit7.mytalkingangela2.nearme.gamecenter",
            "com.outfit7.mytalkingtom2.nearme.gamecenter",
            "com.outfit7.mytalkingtomfree.nearme.gamecenter",
            "com.outfit7.talkingtomgoldrun.nearme.gamecenter",
            "com.pandadastudio.ninjamustdie3.nearme.gamecenter",
            "com.papegames.lysk.cn",
            "com.papegames.lysk.cn.nearme.gamecenter",
            "com.playrix.township.chukong.nearme.gamecenter",
            "com.popcap.pvz2cthdop",
            "com.pwrd.steam.esports",
            "com.qqgame.happymj",
            "com.qqgame.hlddz",
            "com.qqgame.mic",
            "com.qyinter.yuanshenlink",
            "com.rzm.xwdtxsh",
            "com.sabac.hy",
            "com.sgshd.nearme.gamecenter",
            "com.shenlan.m.reverse1999",
            "com.shenlan.m.reverse1999.nearme.gamecenter",
            "com.sinyee.babybus.chants",
            "com.sinyee.babybus.world",
            "com.sofunny.Sausage",
            "com.supercell.boombeach.nearme.gamecenter",
            "com.sykj.tywz.nearme.gamecenter",
            "com.tencent.KiHan",
            "com.tencent.apps.valorant",
            "com.tencent.djcity",
            "com.tencent.fifamobile",
            "com.tencent.fiftyone.yc",
            "com.tencent.game.rhythmmaster",
            "com.tencent.gamehelper.dnf",
            "com.tencent.gate",
            "com.tencent.ig",
            "com.tencent.jkchess",
            "com.tencent.letsgo",
            "com.tencent.lolm",
            "com.tencent.mf.uam",
            "com.tencent.nfsonline",
            "com.tencent.pao",
            "com.tencent.peng",
            "com.tencent.qqgame.xq",
            "com.tencent.qt.qtl",
            "com.tencent.qt.sns",
            "com.tencent.tmgp.NBA",
            "com.tencent.tmgp.cf",
            "com.tencent.tmgp.cod",
            "com.tencent.tmgp.djsy",
            "com.tencent.tmgp.dnf",
            "com.tencent.tmgp.gnyx",
            "com.tencent.tmgp.lv",
            "com.tencent.tmgp.netcraftyouyi",
            "com.tencent.tmgp.pubgmhd",
            "com.tencent.tmgp.qblykilltext",
            "com.tencent.tmgp.qqx5",
            "com.tencent.tmgp.sgame",
            "com.tencent.tmgp.sgamece",
            "com.tencent.tmgp.speedmobile",
            "com.tencent.tmgp.supercell.brawlstars",
            "com.tencent.tmgp.supercell.clashofclans",
            "com.tencent.tmgp.supercell.clashroyale",
            "com.tm.jjxgn.nearme.gamecenter",
            "com.tuyoo.doudizhu.android3d.nearme.gamecenter",
            "com.tuyoo.fish3d.nearme.gamecenter",
            "com.tuyoo.xxbyue4.nearme.gamecenter",
            "com.weile.doudizhu.bytedance.gamecenter",
            "com.wepie.snake.nearme.gamecenter",
            "com.wepie.weplay",
            "com.westhouse.mysjb.xsj",
            "com.xiaomi.gamecenter.sdk.service",
            "com.ychd.mhxjy.nearme.gamecenter",
            "com.yqqsqz.nearme.gamecenter",
            "com.ysch.hxwzqhx.nearme.gamecenter",
            "com.ywxh.xddld.nearme.gamecenter",
            "com.yx.mtrzz.nearme.gamecenter",
            "com.yzxx.tkjssc.nearme.gamecenter",
            "com.zengame.ttddzzrb.nearme.gamecenter",
            "com.zengame.zrttddz.nearme.gamecenter",
            "com.zjgdmj.net.nearme.gamecenter",
            "com.ztgame.bob",
            "com.ztgame.bob.op.nearme.gamecenter",
            "com.zulong.yslzm",
            "game.lbtb.org.cn",
            "honglegeyue.redmoon.cn",
            "org.tinghood.TpsForMobile.nearme.gamecenter",
            "weile.doudizhu.nearme.gamecenter",
    };

    private static final ArraySet<String> INDEX = new ArraySet<>(PACKAGES.length);

    static {
        for (int i = 0; i < PACKAGES.length; i++) {
            INDEX.add(PACKAGES[i]);
        }
    }

    static boolean contains(String packageName) {
        return packageName != null && INDEX.contains(packageName);
    }

    static int size() {
        return PACKAGES.length;
    }

    private ApmGamePackages() {}
}
