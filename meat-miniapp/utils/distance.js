/**
 * 前端球面距离计算工具 (Haversine 算法)
 */
const EARTH_RADIUS = 6371.393; // 单位: km

function toRad(d) {
  return (d * Math.PI) / 180.0;
}

/**
 * 计算经纬度之间的直线距离 (km)
 */
function calculateDistance(lng1, lat1, lng2, lat2) {
  if (!lng1 || !lat1 || !lng2 || !lat2) return 0;
  const radLat1 = toRad(lat1);
  const radLat2 = toRad(lat2);
  const a = radLat1 - radLat2;
  const b = toRad(lng1) - toRad(lng2);

  let s = 2 * Math.asin(Math.sqrt(
    Math.pow(Math.sin(a / 2), 2) +
    Math.cos(radLat1) * Math.cos(radLat2) * Math.pow(Math.sin(b / 2), 2)
  ));
  s = s * EARTH_RADIUS;
  return Number(s.toFixed(2));
}

module.exports = {
  calculateDistance
};
