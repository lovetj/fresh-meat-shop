/**
 * 网络请求封装 request.js
 */
const { BASE_URL, formatImageUrl } = require('./config');
const auth = require('./auth');

let isRefreshing = false;

function request(options) {
  return new Promise((resolve, reject) => {
    const token = auth.getToken();
    let url = options.url || '';
    if (!url.startsWith('http://') && !url.startsWith('https://')) {
      url = `${BASE_URL}${url.startsWith('/') ? url : '/' + url}`;
    }

    wx.request({
      url,
      method: options.method || 'GET',
      data: options.data || {},
      header: {
        'Content-Type': 'application/json',
        Authorization: token ? `Bearer ${token}` : '',
        ...options.header
      },
      success: (res) => {
        if (res.statusCode === 200 && res.data && res.data.code === 200) {
          resolve(res.data.data);
        } else if (res.statusCode === 401 || (res.data && res.data.code === 401)) {
          const isAdminReq = url.includes('/admin/') || url.includes('/shop/admin');
          if (isAdminReq) {
            auth.logoutAdmin();
            if (!isRefreshing && !options.silent) {
              isRefreshing = true;
              wx.showModal({
                title: '掌柜登录已失效',
                content: '您的管理员登录态已到期，请重新登录管理端。',
                confirmText: '去登录',
                success: (modalRes) => {
                  if (modalRes.confirm) {
                    // 「我的」是 tabBar 页面，必须用 switchTab
                    wx.switchTab({ url: '/pages/mine/mine' });
                  }
                  setTimeout(() => { isRefreshing = false; }, 2000);
                }
              });
            }
          } else {
            auth.logoutCustomer();
            if (!isRefreshing && !options.silent) {
              isRefreshing = true;
              wx.showModal({
                title: '登录已失效',
                content: '您的微信登录态已失效，请重新授权登录。',
                confirmText: '去登录',
                success: (modalRes) => {
                  if (modalRes.confirm) {
                    const pages = getCurrentPages();
                    const currentRoute = pages.length ? (pages[pages.length - 1].route || '') : '';
                    // 已在登录页则无需重复跳转
                    if (currentRoute.indexOf('pages/login') === -1) {
                      wx.navigateTo({ url: '/pages/login/login' });
                    }
                  }
                  setTimeout(() => { isRefreshing = false; }, 2000);
                }
              });
            }
          }
          reject(res.data || { code: 401, message: '未登录' });
        } else {
          const errMsg = (res.data && res.data.message) || `请求异常(${res.statusCode})`;
          if (!options.silent) {
            wx.showToast({
              title: errMsg,
              icon: 'none',
              duration: 2500
            });
          }
          reject(res.data || new Error(errMsg));
        }
      },
      fail: (err) => {
        if (!options.silent) {
          wx.showToast({
            title: '网络连接失败，请检查服务',
            icon: 'none'
          });
        }
        reject(err);
      }
    });
  });
}

function uploadFile(options) {
  return new Promise((resolve, reject) => {
    const token = auth.getToken();
    let url = options.url || '/file/upload';
    if (!url.startsWith('http://') && !url.startsWith('https://')) {
      url = `${BASE_URL}${url.startsWith('/') ? url : '/' + url}`;
    }

    wx.uploadFile({
      url,
      filePath: options.filePath,
      name: options.name || 'file',
      formData: options.formData || { module: 'product' },
      header: {
        Authorization: token ? `Bearer ${token}` : '',
        ...options.header
      },
      success: (res) => {
        try {
          const data = typeof res.data === 'string' ? JSON.parse(res.data) : res.data;
          if (data.code === 200) {
            resolve(data.data);
          } else {
            wx.showToast({
              title: data.message || '上传失败',
              icon: 'none'
            });
            reject(data);
          }
        } catch (e) {
          wx.showToast({ title: '解析上传响应失败', icon: 'none' });
          reject(e);
        }
      },
      fail: (err) => {
        wx.showToast({ title: '上传失败', icon: 'none' });
        reject(err);
      }
    });
  });
}

module.exports = {
  request,
  uploadFile,
  get(url, data, options) {
    return request({ url, method: 'GET', data, ...options });
  },
  post(url, data, options) {
    return request({ url, method: 'POST', data, ...options });
  },
  put(url, data, options) {
    return request({ url, method: 'PUT', data, ...options });
  },
  delete(url, data, options) {
    return request({ url, method: 'DELETE', data, ...options });
  },
  del(url, data, options) {
    return request({ url, method: 'DELETE', data, ...options });
  },
  formatImageUrl
};
