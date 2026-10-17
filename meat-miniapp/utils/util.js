const config = require('./config');

/**
 * 通用常用工具函数
 */
function formatTime(date) {
  if (!date) return '';
  const d = new Date(date);
  const year = d.getFullYear();
  const month = (d.getMonth() + 1).toString().padStart(2, '0');
  const day = d.getDate().toString().padStart(2, '0');
  const hour = d.getHours().toString().padStart(2, '0');
  const minute = d.getMinutes().toString().padStart(2, '0');
  return `${year}-${month}-${day} ${hour}:${minute}`;
}

function showToast(title, icon = 'none') {
  wx.showToast({
    title: title || '',
    icon,
    duration: 2000
  });
}

function showLoading(title = '加载中...') {
  wx.showLoading({
    title,
    mask: true
  });
}

function hideLoading() {
  wx.hideLoading();
}

/**
 * 图片公共转换方法：统一拼接 file.base-server
 */
function formatImageUrl(path) {
  return config.formatImageUrl(path);
}

/**
 * 转换为相对路径方法：入库时剔除 baseServer
 */
function toRelativePath(url, relativePath) {
  return config.toRelativePath(url, relativePath);
}

module.exports = {
  formatTime,
  showToast,
  showLoading,
  hideLoading,
  formatImageUrl,
  toRelativePath
};

