package com.freshmeat.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 经纬度距离计算工具类 (基于球面三角 Haversine 公式)
 */
public class DistanceUtil {

    /** 地球平均半径，单位：千米(km) */
    private static final double EARTH_RADIUS = 6371.393;

    /**
     * 计算两点经纬度之间的直线球面距离 (单位: km)
     *
     * @param lng1 点1经度
     * @param lat1 点1纬度
     * @param lng2 点2经度
     * @param lat2 点2纬度
     * @return 距离 (千米，保留两位小数)
     */
    public static double calculateDistance(double lng1, double lat1, double lng2, double lat2) {
        double radLat1 = Math.toRadians(lat1);
        double radLat2 = Math.toRadians(lat2);
        double a = radLat1 - radLat2;
        double b = Math.toRadians(lng1) - Math.toRadians(lng2);

        double s = 2 * Math.asin(Math.sqrt(
                Math.pow(Math.sin(a / 2), 2) +
                Math.cos(radLat1) * Math.cos(radLat2) * Math.pow(Math.sin(b / 2), 2)
        ));

        s = s * EARTH_RADIUS;
        BigDecimal bd = BigDecimal.valueOf(s).setScale(2, RoundingMode.HALF_UP);
        return bd.doubleValue();
    }

    /**
     * 判断收货地址是否在商铺支持的最大配送半径之内
     *
     * @param shopLng 店铺经度
     * @param shopLat 店铺纬度
     * @param userLng 收货人经度
     * @param userLat 收货人纬度
     * @param maxDistance 最大配送范围 (km)
     * @return true-可配送，false-超出距离
     */
    public static boolean isWithinDeliveryDistance(double shopLng, double shopLat,
                                                  double userLng, double userLat,
                                                  double maxDistance) {
        double actualDistance = calculateDistance(shopLng, shopLat, userLng, userLat);
        return actualDistance <= maxDistance;
    }
}
