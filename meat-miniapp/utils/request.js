/**
 * 网络请求封装 request.js
 */
const { BASE_URL, formatImageUrl } = require('./config');
const auth = require('./auth');

// 业务码：账号已在其他设备登录（被顶下线），后端 JwtInterceptor 返回
const CODE_SESSION_KICKED = 4011;
// 登录失效提示（被顶下线 / Token 过期）弹窗是否正在展示，避免并发请求重复弹窗
let isAuthTipShowing = false;
// 登录态巡检请求去重
let sessionCheckPromise = null;

/**
 * 发起本次请求时使用的 Token 是否仍在使用中
 * 若期间已重新登录（Token 已被替换），说明这是过期响应，不再打扰用户
 */
function isTokenStillInUse(token) {
  if (!token) return false;
  return token === auth.getAdminToken() || token === auth.getCustomerToken();
}

/** Token 归属身份（管理员 / 顾客），用于清理对应凭证 */
function roleOfToken(token) {
  return token && token === auth.getAdminToken() ? 'ADMIN' : 'CUSTOMER';
}

/** 同步全局登录态，避免清理凭证后其它页面仍读到旧的 globalData */
function syncGlobalLoginState() {
  try {
    const app = getApp();
    if (app && app.globalData) {
      app.globalData.userInfo = auth.getUserInfo();
      app.globalData.isAdmin = auth.isAdmin();
    }
  } catch (e) {}
}

/** 让当前页面立即按新的登录态重新渲染（页面未提供刷新方法时忽略） */
function refreshCurrentPage() {
  try {
    const pages = getCurrentPages();
    const page = pages && pages.length ? pages[pages.length - 1] : null;
    if (!page) return;
    if (typeof page.refreshPageData === 'function') {
      page.refreshPageData();
    } else if (typeof page.refreshUserInfo === 'function') {
      page.refreshUserInfo();
    }
    // 登录态变化后购物车要按新身份重算，避免退出登录后角标/抽屉仍显示旧数量
    if (typeof page.refreshCart === 'function') {
      page.refreshCart();
    }
  } catch (e) {}
}

/**
 * 退出账号（在被踢提示中选择「退出账号」）
 * 掌柜只退出管理端；顾客彻底退出并保持未登录（下次启动不再静默登录）
 */
function signOut(role) {
  if (role === 'ADMIN') {
    auth.logoutAdmin();
    syncGlobalLoginState();
    refreshCurrentPage();
    wx.showToast({ title: '已退出掌柜账号', icon: 'none' });
    wx.switchTab({ url: '/pages/mine/mine' });
    return;
  }

  auth.clearLoginState();
  auth.setLoggedOut(true);
  syncGlobalLoginState();
  refreshCurrentPage();
  wx.showToast({ title: '已退出账号', icon: 'none' });
  wx.switchTab({ url: '/pages/mine/mine' });
}

/**
 * 跳转登录页
 * 掌柜被顶下线时带上 admin=1，登录页会直接弹出账号密码登录框，
 * 避免用户还要自己再找掌柜入口。
 */
function goToLogin(role) {
  const isAdmin = role === 'ADMIN';
  const target = isAdmin ? '/pages/login/login?admin=1' : '/pages/login/login';
  const pages = getCurrentPages();

  if (!pages || !pages.length) {
    wx.reLaunch({ url: target });
    return;
  }

  const current = pages[pages.length - 1];
  const route = (current && current.route) || '';
  // 已在登录页则无需重复跳转，掌柜场景直接打开登录弹窗
  if (route.indexOf('pages/login') !== -1) {
    if (isAdmin && current && typeof current.openAdminModal === 'function') {
      current.openAdminModal();
    }
    return;
  }

  wx.navigateTo({ url: target, fail: () => wx.reLaunch({ url: target }) });
}

/**
 * 展示「重新登录 / 退出账号」弹窗
 * 冷启动阶段页面栈可能尚未就绪，此时稍作重试，避免提示丢失
 */
function promptRelogin(cfg, retry) {
  const pages = getCurrentPages();
  if ((!pages || !pages.length) && retry < 10) {
    setTimeout(() => promptRelogin(cfg, retry + 1), 200);
    return;
  }

  wx.showModal({
    title: cfg.title,
    content: cfg.content,
    confirmText: '重新登录',
    cancelText: '退出账号',
    confirmColor: '#FF6B6B',
    success: (modalRes) => {
      if (modalRes.confirm) {
        goToLogin(cfg.role);
      } else {
        signOut(cfg.role);
      }
      setTimeout(() => { isAuthTipShowing = false; }, 1200);
    },
    fail: () => { isAuthTipShowing = false; }
  });
}

/**
 * 统一处理登录态失效（被顶下线 / Token 过期）
 * 关键：这里不区分 silent 请求。登录态失效是安全事件，
 * 静默请求（「我的」页 /user/info、后台数据预加载等）也必须提示重新登录，
 * 否则用户停留在这些页面时永远看不到「重新登录」，必须切页面才触发。
 */
function handleAuthInvalid(cfg) {
  const { token, role } = cfg;
  if (!isTokenStillInUse(token)) return;
  if (isAuthTipShowing) return;
  isAuthTipShowing = true;

  // 旧 Token 已被服务端判定失效，先移除本地凭证（保留购物车等本地数据），
  // 避免后续请求继续携带失效 Token 反复失败
  auth.clearToken(role);
  syncGlobalLoginState();
  refreshCurrentPage();

  // loading 遮罩会挡住弹窗，先收起
  wx.hideLoading();

  // 稍后弹出：部分页面在 catch 里还会再弹自己的提示框，
  // 延后一点可确保「重新登录」弹窗最后弹出、盖在最上层
  setTimeout(() => promptRelogin(cfg, 0), 150);
}

/**
 * 处理「账号已在其他设备登录，本机被顶下线」
 */
function handleSessionKicked(token) {
  if (!isTokenStillInUse(token)) return;
  handleAuthInvalid({
    token,
    role: roleOfToken(token),
    title: '账号已在其他设备登录',
    content: '当前账号已在其他设备登录，本机登录状态已失效，请重新登录。'
  });
}

/** 登录态过期 / 无效 */
function handleSessionExpired(token) {
  if (!isTokenStillInUse(token)) return;
  const role = roleOfToken(token);
  const isAdmin = role === 'ADMIN';
  handleAuthInvalid({
    token,
    role,
    title: isAdmin ? '掌柜登录已失效' : '登录已失效',
    content: isAdmin
      ? '您的管理员登录态已到期，请重新登录管理端。'
      : '您的微信登录态已失效，请重新授权登录。'
  });
}

/**
 * 登录态巡检：校验本地 Token 在服务端是否仍是当前有效会话
 * 供小程序前台定时调用：被其他设备顶下线后，无需用户切换页面即可提示重新登录
 * @returns {Promise<Boolean>} 登录态是否仍然有效
 */
function checkSession() {
  if (sessionCheckPromise) return sessionCheckPromise;

  const token = auth.getToken();
  if (!token) return Promise.resolve(false);

  sessionCheckPromise = new Promise((resolve) => {
    wx.request({
      url: `${BASE_URL}/user/session`,
      method: 'GET',
      header: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`
      },
      success: (res) => {
        const data = res.data || {};
        if (data.code === CODE_SESSION_KICKED) {
          handleSessionKicked(token);
          resolve(false);
          return;
        }
        if (res.statusCode === 401 || data.code === 401) {
          handleSessionExpired(token);
          resolve(false);
          return;
        }
        resolve(data.code === 200);
      },
      // 网络异常不代表登录态失效，避免误踢
      fail: () => resolve(true)
    });
  }).then((valid) => {
    sessionCheckPromise = null;
    return valid;
  }, (err) => {
    sessionCheckPromise = null;
    throw err;
  });

  return sessionCheckPromise;
}

/** 从 Authorization 头中取出实际发送的 Token */
function extractToken(authHeader) {
  const value = String(authHeader || '').trim();
  if (!value) return '';
  return value.replace(/^Bearer\s*/i, '').trim();
}

function request(options) {
  return new Promise((resolve, reject) => {
    const token = auth.getToken();
    let url = options.url || '';
    if (!url.startsWith('http://') && !url.startsWith('https://')) {
      url = `${BASE_URL}${url.startsWith('/') ? url : '/' + url}`;
    }

    const header = {
      'Content-Type': 'application/json',
      Authorization: token ? `Bearer ${token}` : '',
      ...options.header
    };
    // 页面可能显式指定身份 Token（如「我的」页严格使用当前生效身份），
    // 失效处理必须针对真正发出去的那个 Token，否则会清错凭证
    const sentToken = extractToken(header.Authorization) || token;

    wx.request({
      url,
      method: options.method || 'GET',
      data: options.data || {},
      header,
      success: (res) => {
        if (res.statusCode === 200 && res.data && res.data.code === 200) {
          resolve(res.data.data);
        } else if (res.data && res.data.code === CODE_SESSION_KICKED) {
          // 账号已在其他设备登录，本机被顶下线：提示重新登录或退出账号
          handleSessionKicked(sentToken);
          reject(res.data);
        } else if (res.statusCode === 401 || (res.data && res.data.code === 401)) {
          // 登录态过期 / 无效：同样必须提示（silent 请求也不例外）
          handleSessionExpired(sentToken);
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

    const header = {
      Authorization: token ? `Bearer ${token}` : '',
      ...options.header
    };
    const sentToken = extractToken(header.Authorization) || token;

    wx.uploadFile({
      url,
      filePath: options.filePath,
      name: options.name || 'file',
      formData: options.formData || { module: 'product' },
      header,
      success: (res) => {
        try {
          const data = typeof res.data === 'string' ? JSON.parse(res.data) : res.data;
          if (data.code === CODE_SESSION_KICKED) {
            // 上传时同样可能被顶下线，统一走重新登录/退出账号提示
            handleSessionKicked(sentToken);
            reject(data);
            return;
          }
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
  checkSession,
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
