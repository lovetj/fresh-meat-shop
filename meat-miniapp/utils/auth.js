/**
 * 鲜肉铺 - 微信顾客与超级管理员鉴权体系（双轨解耦认证）
 */
const { BASE_URL } = require('./config');

// 顾客端：微信快捷登录态 (Token有效期30天)
const KEY_CUSTOMER_TOKEN = 'MEAT_CUSTOMER_TOKEN';
const KEY_CUSTOMER_USER = 'MEAT_CUSTOMER_USER_INFO';

// 掌柜管理端：超级管理员账号密码登录态 (Token有效期7天)
const KEY_ADMIN_TOKEN = 'MEAT_ADMIN_TOKEN';
const KEY_ADMIN_USER = 'MEAT_ADMIN_USER_INFO';

// 当前生效身份：ADMIN(掌柜) / CUSTOMER(微信顾客)，决定个人中心展示哪一套界面
const KEY_ACTIVE_ROLE = 'MEAT_ACTIVE_ROLE';

// 用户主动退出登录标记：为 true 时不再自动静默登录，保持未登录状态
const KEY_LOGGED_OUT = 'MEAT_LOGGED_OUT';

// 兼容键名（用于兼容部分老代码）
const KEY_LEGACY_TOKEN = 'MEAT_TOKEN';
const KEY_LEGACY_USER = 'MEAT_USER_INFO';

const auth = {
  // 获取顾客 Token
  getCustomerToken() {
    return wx.getStorageSync(KEY_CUSTOMER_TOKEN) || wx.getStorageSync(KEY_LEGACY_TOKEN) || '';
  },

  setCustomerToken(token) {
    wx.setStorageSync(KEY_CUSTOMER_TOKEN, token);
    wx.setStorageSync(KEY_LEGACY_TOKEN, token);
  },

  getCustomerUserInfo() {
    return wx.getStorageSync(KEY_CUSTOMER_USER) || wx.getStorageSync(KEY_LEGACY_USER) || null;
  },

  setCustomerUserInfo(user) {
    wx.setStorageSync(KEY_CUSTOMER_USER, user);
    wx.setStorageSync(KEY_LEGACY_USER, user);
  },

  // 获取管理员 Token
  getAdminToken() {
    return wx.getStorageSync(KEY_ADMIN_TOKEN) || '';
  },

  setAdminToken(token) {
    wx.setStorageSync(KEY_ADMIN_TOKEN, token);
  },

  getAdminUserInfo() {
    return wx.getStorageSync(KEY_ADMIN_USER) || null;
  },

  setAdminUserInfo(user) {
    wx.setStorageSync(KEY_ADMIN_USER, user);
  },

  /**
   * 智能获取当前请求适用的 Token：
   * 1. 若当前在商户后台页面栈（/pages/merchant/...），优先使用管理员 Token；
   * 2. 若在顾客端普通页面，优先使用微信顾客 Token；
   * 3. 兜底回退：若只登录了其中一种身份，则使用已登录的身份 Token。
   */
  getToken() {
    const adminToken = this.getAdminToken();
    const customerToken = this.getCustomerToken();

    try {
      const pages = getCurrentPages();
      if (pages && pages.length > 0) {
        const currentPage = pages[pages.length - 1];
        const route = currentPage ? (currentPage.route || '') : '';
        if (route.includes('pages/merchant/')) {
          return adminToken || customerToken;
        }
      }
    } catch (e) {}

    return customerToken || adminToken;
  },

  /**
   * 严格取「当前生效身份」对应的 Token
   * 用于必须写到自己账号上的操作（如更新本人头像资料），避免顾客/掌柜 Token 串号
   */
  getActiveToken() {
    const role = this.getActiveRole();
    if (role === 'ADMIN') return this.getAdminToken();
    if (role === 'CUSTOMER') return this.getCustomerToken();
    return '';
  },

  // 兼容老调用
  setToken(token) {
    this.setCustomerToken(token);
  },

  /**
   * 当前生效身份：ADMIN / CUSTOMER / ''(未登录)
   * 只有"最近一次登录"的身份生效，避免管理员 Token 常驻导致顾客端也显示掌柜界面
   */
  getActiveRole() {
    const role = wx.getStorageSync(KEY_ACTIVE_ROLE) || '';

    if (role === 'ADMIN') {
      return this.getAdminToken() ? 'ADMIN' : '';
    }
    if (role === 'CUSTOMER') {
      return this.getCustomerToken() ? 'CUSTOMER' : '';
    }

    // 兼容历史数据：未记录身份时按已有 Token 推断（管理员优先，保持7天免登）
    if (this.getAdminToken()) return 'ADMIN';
    if (this.getCustomerToken()) return 'CUSTOMER';
    return '';
  },

  setActiveRole(role) {
    if (role) {
      wx.setStorageSync(KEY_ACTIVE_ROLE, role);
    } else {
      wx.removeStorageSync(KEY_ACTIVE_ROLE);
    }
  },

  // 是否由用户主动退出登录（主动退出后不再自动静默登录）
  isLoggedOut() {
    return !!wx.getStorageSync(KEY_LOGGED_OUT);
  },

  setLoggedOut(flag) {
    if (flag) {
      wx.setStorageSync(KEY_LOGGED_OUT, true);
    } else {
      wx.removeStorageSync(KEY_LOGGED_OUT);
    }
  },

  /**
   * 获取当前身份对应的用户信息（isAdmin 只由当前身份决定，不再使用后端账号角色）
   */
  getUserInfo() {
    const role = this.getActiveRole();

    if (role === 'ADMIN') {
      const adminUser = this.getAdminUserInfo();
      if (adminUser) {
        return { ...adminUser, isAdmin: true };
      }
    }

    if (role === 'CUSTOMER') {
      const customerUser = this.getCustomerUserInfo();
      if (customerUser) {
        return { ...customerUser, isAdmin: false };
      }
    }

    return null;
  },

  /**
   * 缓存用户信息（按当前身份写入对应存储，避免顾客资料被写进管理员缓存）
   */
  setUserInfo(userInfo) {
    if (!userInfo) return;
    if (this.getActiveRole() === 'ADMIN') {
      this.setAdminUserInfo(userInfo);
    } else {
      this.setCustomerUserInfo(userInfo);
    }
  },

  /**
   * 是否处于掌柜管理员身份
   */
  isAdmin() {
    return this.getActiveRole() === 'ADMIN';
  },

  /**
   * 是否已登录（存在生效身份）
   */
  isLoggedIn() {
    return !!this.getActiveRole();
  },

  /**
   * 登录拦截：未登录时弹窗提示，确认后跳转登录页
   * @param {Object} options 可选 { title, content }
   * @returns {boolean} 已登录返回 true；未登录返回 false
   */
  requireLogin(options) {
    if (this.isLoggedIn()) return true;

    const opt = options || {};
    wx.showModal({
      title: opt.title || '请先登录',
      content: opt.content || '登录后才能使用该功能',
      confirmText: '去登录',
      cancelText: '暂不登录',
      confirmColor: '#FF6B6B',
      success: (res) => {
        if (res.confirm) {
          wx.navigateTo({ url: '/pages/login/login' });
        }
      }
    });
    return false;
  },

  /**
   * 退出管理员模式（切回纯顾客态）
   */
  logoutAdmin() {
    wx.removeStorageSync(KEY_ADMIN_TOKEN);
    wx.removeStorageSync(KEY_ADMIN_USER);
    this.setActiveRole(this.getCustomerToken() ? 'CUSTOMER' : '');
  },

  /**
   * 退出微信顾客态
   */
  logoutCustomer() {
    const wasAdmin = wx.getStorageSync(KEY_ACTIVE_ROLE) === 'ADMIN';

    wx.removeStorageSync(KEY_CUSTOMER_TOKEN);
    wx.removeStorageSync(KEY_CUSTOMER_USER);
    wx.removeStorageSync(KEY_LEGACY_TOKEN);
    wx.removeStorageSync(KEY_LEGACY_USER);

    // 同步清空所有前端购物车本地缓存
    this.clearCartStorage();

    // 顾客退出后，若原本就是管理员身份则保持掌柜态，否则回到未登录
    this.setActiveRole(wasAdmin && this.getAdminToken() ? 'ADMIN' : '');
  },

  /**
   * 完全退出所有登录
   */
  clearLoginState() {
    wx.removeStorageSync(KEY_CUSTOMER_TOKEN);
    wx.removeStorageSync(KEY_CUSTOMER_USER);
    wx.removeStorageSync(KEY_LEGACY_TOKEN);
    wx.removeStorageSync(KEY_LEGACY_USER);
    wx.removeStorageSync(KEY_ADMIN_TOKEN);
    wx.removeStorageSync(KEY_ADMIN_USER);

    // 同步清空所有前端购物车本地缓存
    this.clearCartStorage();

    this.setActiveRole('');
  },

  /**
   * 清除购物车本地缓存
   */
  clearCartStorage() {
    try {
      const res = wx.getStorageInfoSync();
      if (res && res.keys) {
        res.keys.forEach(k => {
          if (k.startsWith('MEAT_CART_')) {
            wx.removeStorageSync(k);
          }
        });
      }
    } catch (e) {}
  },

  /**
   * 微信顾客一键授权登录 (角色恒定为 CUSTOMER)
   * @param {Object} extraUserInfo 微信授权资料 { nickname, avatarUrl, phone }
   * @param {Object} options { forceRole: 'CUSTOMER' } 显式登录时强制切换为顾客身份
   */
  login(extraUserInfo = {}, options = {}) {
    return new Promise((resolve, reject) => {
      wx.login({
        success: (loginRes) => {
          if (!loginRes.code) {
            return reject(new Error('微信登录凭证获取失败'));
          }

          wx.request({
            url: `${BASE_URL}/user/wx/login`,
            method: 'POST',
            data: {
              code: loginRes.code,
              nickname: extraUserInfo.nickname || '',
              avatarUrl: extraUserInfo.avatarUrl || '',
              phone: extraUserInfo.phone || ''
            },
            header: {
              'Content-Type': 'application/json'
            },
            success: (res) => {
              if (res.statusCode === 200 && res.data && res.data.code === 200) {
                const data = res.data.data;
                auth.setCustomerToken(data.token);
                auth.setCustomerUserInfo(data);

                // 显式微信登录（登录页）强制切到顾客身份；
                // 启动静默登录则保留已有的管理员身份，保证掌柜 7 天免登
                const nextRole = options.forceRole ||
                  (auth.getActiveRole() === 'ADMIN' ? 'ADMIN' : 'CUSTOMER');
                auth.setActiveRole(nextRole);
                auth.setLoggedOut(false);

                resolve(data);
              } else {
                reject(res.data || new Error('微信登录失败'));
              }
            },
            fail: (err) => reject(err)
          });
        },
        fail: (err) => reject(err)
      });
    });
  },

  /**
   * 超级管理员账号密码登录 (高权 ADMIN 鉴权)
   */
  loginAdmin(username, password) {
    return new Promise((resolve, reject) => {
      if (!username || !username.trim()) {
        return reject(new Error('请输入管理员账号'));
      }
      if (!password || !password.trim()) {
        return reject(new Error('请输入管理员密码'));
      }

      wx.request({
        url: `${BASE_URL}/user/admin/login`,
        method: 'POST',
        data: {
          username: username.trim(),
          password: password.trim()
        },
        header: {
          'Content-Type': 'application/json'
        },
        success: (res) => {
          if (res.statusCode === 200 && res.data && res.data.code === 200) {
            const data = res.data.data;
            auth.setAdminToken(data.token);
            auth.setAdminUserInfo(data);
            auth.setActiveRole('ADMIN');
            auth.setLoggedOut(false);
            resolve(data);
          } else {
            const msg = (res.data && res.data.message) || '账号或密码错误';
            reject(new Error(msg));
          }
        },
        fail: (err) => reject(err)
      });
    });
  }
};

module.exports = auth;
