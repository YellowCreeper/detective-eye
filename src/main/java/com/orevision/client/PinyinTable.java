package com.orevision.client;

import java.util.HashMap;
import java.util.Map;

/**
 * 原版方块名里出现的汉字的多读音表，格式为 字:读音1,读音2，读音已去声调、ü 记作 v。
 * 由 tmp/gen_pinyin.ps1 从 zh_cn.json + pinyin-data (MIT) 生成，不要手改。
 */
final class PinyinTable {
    private static final Map<Character, String[]> TABLE = new HashMap<>();

    static {
        String data =
                "丁:ding,zheng|三:san|上:shang|下:xia|不:bu,fou,fu|丛:cong|丝:si|中:zhong|丹:dan|主:zhu|之:zhi,zhu|书:shu|" +
                "了:le,liao|云:yun|互:hu|交:jiao|人:ren|仙:xian|令:ling,lian|休:xiu,xu|传:chuan,zhuan|位:wei,li|体:ti,ben,cui|作:zuo|" +
                "你:ni|例:li,lie|侦:zhen|信:xin,shen|傀:gui,kui,kuai|催:cui|像:xiang|僵:jiang|儡:lei|充:chong|光:guang|克:ke|" +
                "入:ru|公:gong|兰:lan|兽:shou|冰:bing,ning|凋:diao|凝:ning|切:qie,qi|制:zhi|刷:shua|刻:ke,kei|力:li|" +
                "动:dong|助:zhu,chu|化:hua,huo|匿:ni,te|十:shi|半:ban,pan|南:nan,na|卜:bo,bu,pu|占:zhan,tie|卵:luan,kun|压:ya|原:yuan|" +
                "去:qu|发:fa|古:gu,ku|只:zhi|可:ke,ge|台:tai,yi,si|右:you|叶:ye,xie|合:he,ge|向:xiang|告:gao,ju,gu|周:zhou|" +
                "命:ming|品:pin|哭:ku|唱:chang|啸:xiao|嗅:xiu|嘎:ga|器:qi|团:tuan,qiu|围:wei|图:tu|圆:yuan|" +
                "土:tu,du,cha|在:zai|地:di,de|坏:huai,pi,pei|块:kuai,yue|坯:pi,huai|垂:chui,zhui|型:xing|基:ji|堆:dui,zui|塞:sai,se|墙:qiang|" +
                "夜:ye|大:da,dai,tai|太:tai,ta|失:shi,yi|头:tou|子:zi|孔:kong|字:zi|存:cun|孢:bao|安:an|宝:bao|" +
                "实:shi|家:jia,jie,gu|射:she,ye,yi|小:xiao|尖:jian|尸:shi|层:ceng|屏:ping,bing|展:zhan|山:shan|岗:gang|岩:yan|" +
                "巢:chao|工:gong|左:zuo|已:yi,si|巴:ba|布:bu|帜:zhi|带:dai|干:gan,an|平:ping,pian,bing,beng|幽:you|床:chuang|" +
                "库:ku|底:di,de|开:kai|异:yi|式:shi,te|张:zhang|强:qiang,jiang|形:xing|影:ying|径:jing|循:xun|徽:hui|" +
                "心:xin|怕:pa,bo|性:xing|怨:yuan,yun|怪:guai|息:xi|恶:e,wu|悬:xuan|感:gan,han|成:cheng|或:huo,yu|扇:shan|" +
                "手:shou|把:ba,pa|投:tou,dou|折:zhe,she,ti|拉:la|拼:pin,bing|挂:gua|按:an|挡:dang|捆:kun,hun|损:sun|掌:zhang|" +
                "探:tan,xian|掷:zhi|插:cha,zha|效:xiao|斑:ban|斗:dou,zhu|斜:xie,xia,cha,ye|方:fang,pang,wang,feng|旋:xuan|旗:qi|日:ri|时:shi|" +
                "明:ming,meng|晶:jing|暗:an|暴:bao,pu,bo|曜:yao|有:you,wei|木:mu|末:mo,me|朱:zhu,shu|朵:duo|机:ji,wei|杆:gan|" +
                "杉:shan,sha|杜:du,tu|条:tiao|杨:yang|板:ban|构:gou|林:lin|果:guo,luo,guan|枝:zhi,qi|枯:ku,gu|架:jia|柄:bing|" +
                "染:ran|柱:zhu|栅:zha,shan,ce|标:biao|栏:lan|树:shu|校:xiao,jiao,qiao|株:zhu|核:he,hu,gai,kai|根:gen|格:ge,luo,he|栽:zai|" +
                "框:kuang|桦:hua|桶:tong|梯:ti|棕:zong|植:zhi|楼:lou|横:heng,guang,huang|樱:ying|橙:cheng,deng,chen|橡:xiang|欢:huan|" +
                "正:zheng|武:wu|残:can|母:mu,wu|比:bi,pi|毛:mao|毯:tan|气:qi|氧:yang|水:shui|沉:chen|沙:sha,suo|" +
                "沾:zhan,tian,dian,chan|泡:pao|波:bo,bei,bi|泣:qi,li,se|泥:ni,nie,ning|泪:lei|洞:dong,tong|活:huo,guo|流:liu|浆:jiang|测:ce|浮:fu|" +
                "海:hai|涂:tu,chu,ye|涌:yong,chong|涡:wo,guo|液:ye,shi|淡:dan,yan,tan|深:shen|混:hun,gun,kun|渐:jian|游:you,liu|湿:shi|源:yuan|" +
                "滑:hua,gu|滨:bin|滴:di|漏:lou|潜:qian|潮:chao|激:ji,jiao|灌:guan,huan|火:huo|灯:deng,ding|灰:hui|灵:ling|" +
                "炉:lu|炭:tan|炸:zha|点:dian|炼:lian|烈:lie|烛:zhu,chong|烟:yan,yin|煤:mei|熏:xun|熔:rong|爆:bao,bo|" +
                "片:pian,pan|牌:pai|牡:mu|物:wu|猪:zhu|玄:xuan|玩:wan|玫:mei|环:huan|现:xian|玻:bo|珀:po|" +
                "珊:shan|珠:zhu|球:qiu|瑚:hu|瑰:gui|璃:li|瓜:gua|瓦:wa|瓶:ping|甘:gan,han|甜:tian|生:sheng|" +
                "用:yong|界:jie|疑:yi,ning|疣:you|白:bai,bo|的:de,di|皮:pi|盆:pen|盒:he,an|盛:sheng,cheng|盾:dun,shun,yun|眠:mian,min|" +
                "眸:mou|眼:yan,wen|睡:shui|矢:shi|矮:ai|石:shi,dan|矿:kuang|砂:sha|砖:zhuan|砧:zhen|砾:li|硫:liu,chu|" +
                "磁:ci|磨:mo|示:shi,qi,zhi|祥:xiang|禁:jin|秆:gan|种:zhong,chong|移:yi,chi|穴:xue,jue|空:kong|竖:shu|竹:zhu|" +
                "笋:sun|符:fu|笼:long|管:guan|箭:jian|箱:xiang|簇:cu,chuo,cou|类:lei|粉:fen|粗:cu|糕:gao|索:suo|" +
                "紫:zi|红:hong,gong|纹:wen|线:xian|细:xi|织:zhi|绊:ban|绒:rong|结:jie|络:luo,lao|继:ji|绯:fei|" +
                "绵:mian|绿:lv,lu|缠:chan|罐:guan|网:wang|置:zhi|羊:yang|美:mei|翠:cui|耕:geng|耳:er,reng|肥:fei,bi|" +
                "胎:tai|胡:hu|能:neng,tai,nai,xiong|脂:zhi|脆:cui|脉:mai,mo|脑:nao|脚:jiao,jue|脾:pi,pai,bi|自:zi|色:se,shai|花:hua|" +
                "芽:ya|苍:cang|苔:tai|苗:miao|苦:ku,gu,hu|英:ying,yang|茎:jing|草:cao,zao|荡:dang|荧:ying|药:yao|莲:lian|" +
                "菇:gu|菊:ju|菌:jun|菜:cai|菱:ling|萎:wei|萝:luo|萤:ying|营:ying|葱:cong,chuang|葵:kui|蒲:pu,bo|" +
                "蓝:lan,la|蔓:man,wan|蔗:zhe|蕨:jue|薯:shu|藓:xian|藤:teng|蘑:mo|虚:xu|虞:yu|虫:chong,hui|蚀:shi|" +
                "蛋:dan|蛙:wa,jue|蛛:zhu|蜂:feng|蜘:zhi|蜜:mi|蜡:la,qu,zha,ji|衣:yi|被:bei,bi,pi|裂:lie|装:zhuang|西:xi|" +
                "覆:fu|角:jiao,jue,lu,gu|解:jie,xie|讲:jiang|设:she|试:shi|诡:gui|质:zhi|赛:sai|赭:zhe|跃:yue|车:che,ju|" +
                "轨:gui|轮:lun|轻:qing|较:jiao|辅:fu|边:bian|这:zhe,zhei|远:yuan|连:lian|送:song|造:zao,cao|遮:zhe|" +
                "避:bi|郁:yu|酿:niang|釉:you|重:zhong,chong,tong|野:ye,shu|金:jin|针:zhen|钟:zhong|钩:gou|钮:niu|钻:zuan|" +
                "铁:tie|铃:ling|铜:tong|链:lian|锁:suo|锅:guo|锈:xiu|锚:mao|锥:zhui|锻:duan|镶:xiang|长:zhang,chang|" +
                "门:men|闪:shan|闭:bi|间:jian|阱:jing|阳:yang|阶:jie|阻:zu,zhu|附:fu,bu|陶:tao,yao,dao|陷:xian|障:zhang|" +
                "雕:diao|雪:xue|雷:lei|霜:shuang|青:qing,jing|靶:ba|音:yin|顶:ding|须:xu|颂:song|颅:lu|频:pin|" +
                "风:feng|饰:shi|首:shou|香:xiang|马:ma|驳:bo|骨:gu|骷:ku|骸:hai,gai|髅:lou|高:gao|魂:hun|" +
                "魔:mo|鹃:juan|鹿:lu,lv|麦:mai|黄:huang|黏:nian|黑:hei|鼻:bi|龙:long|龟:gui,jun,qiu";
        for (String entry : data.split("\\|")) {
            int colon = entry.indexOf(58);
            if (colon <= 0) {
                continue;
            }
            TABLE.put(entry.charAt(0), entry.substring(colon + 1).split(","));
        }
    }

    private PinyinTable() {
    }

    /** 该字的全部读音（无音调），未收录返回 null。 */
    static String[] of(char c) {
        return TABLE.get(c);
    }
}
