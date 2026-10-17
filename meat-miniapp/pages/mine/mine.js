// pages/mine/mine.js
const auth = require('../../utils/auth');
const request = require('../../utils/request');
const { formatImageUrl } = require('../../utils/util');
const app = getApp();

// 后端新用户默认昵称，视为"未完善资料"
const DEFAULT_NICKNAME = '微信顾客';

Page({
  data: {
    userInfo: null,
    isLogin: false,
    isAdmin: false,
    hasProfile: false,
    shopInfo: {}
  },

  onShow() {
    this.refreshPageData();
    // 同步自定义 tabBar 选中态
    if (typeof this.getTabBar === 'function' && this.getTabBar()) {
      this.getTabBar().setData({ selected: 2 });
    }
  },

  /**
   * 刷新本页涉及的全部接口：用户信息 + 店铺信息
   * 每次切回「我的」页签都会执行
   */
  refreshPageData() {
    return Promise.all([
      this.refreshUserInfo(),
      this.loadShopInfo()
    ]);
  },

  formatUser(u) {
    if (!u) return null;
    return {
      ...u,
      // 头像统一动态拼接前端 fileBaseServer（若为第三方完整外链则原样保留）
      avatarUrl: formatImageUrl(u.avatarUrl)
    };
  },

  // 是否已完善微信头像或昵称（静默登录用户默认为"微信顾客"且无头像）
  checkProfile(user) {
    if (!user) return false;
    const nickname = (user.nickname || '').trim();
    return (!!nickname && nickname !== DEFAULT_NICKNAME) || !!user.avatarUrl;
  },

  refreshUserInfo() {
    // 请求序号：退出登录或在途请求返回时，避免旧数据把登录态写回来
    const seq = (this._userReqSeq = (this._userReqSeq || 0) + 1);

    // 当前身份只由「最近一次登录方式」决定：ADMIN 掌柜 / CUSTOMER 顾客 / 未登录
    const activeRole = auth.getActiveRole();
    const isLogin = !!activeRole;
    const isAdmin = activeRole === 'ADMIN';
    const rawUserInfo = auth.getUserInfo();

    this.setData({
      isLogin,
      isAdmin,
      userInfo: this.formatUser(rawUserInfo),
      hasProfile: this.checkProfile(rawUserInfo)
    });

    if (!isLogin) return Promise.resolve();

    return request.get('/user/info', {}, {
      silent: true,
      // 严格使用当前身份（顾客/掌柜）对应的 Token，避免拿错账号的资料覆盖自己
      header: { Authorization: `Bearer ${auth.getActiveToken()}` }
    }).then((res) => {
      // 已退出登录或已有更新的请求，丢弃过期响应，不再回写用户信息
      if (seq !== this._userReqSeq || !auth.isLoggedIn()) return;
      if (res) {
        auth.setUserInfo(res);
        this.setData({
          userInfo: this.formatUser(res),
          hasProfile: this.checkProfile(res)
          // 注意：不使用接口返回的 isAdmin（那是账号在库里的角色），
          // 界面身份只看本次登录的是顾客还是掌柜
        });
      }
    }).catch(console.error);
  },

  /**
   * 还原为未登录（首次进入）状态：
   * 清空页面数据、全局用户信息，并让在途的用户信息请求失效
   */
  resetToGuest() {
    this._userReqSeq = (this._userReqSeq || 0) + 1;

    app.globalData.userInfo = null;
    app.globalData.isAdmin = false;

    this.setData({
      userInfo: null,
      isLogin: false,
      isAdmin: false,
      hasProfile: false
    });
  },

  loadShopInfo() {
    return request.get('/shop/info', {}, { silent: true }).then((res) => {
      if (res) {
        this.setData({ shopInfo: res });
      }
    }).catch(console.error);
  },

  /**
   * 进入微信授权登录页（获取/更新头像与昵称）
   * 掌柜身份下禁止直接跳转：微信授权登录会强制切换为顾客身份并清掉管理员 Token，
   * 造成「编辑资料」把当前管理员登录态顶掉。这里改为二次确认后先切回顾客态。
   */
  onLogin() {
    if (this.data.isAdmin) {
      wx.showModal({
        title: '当前为掌柜身份',
        content: '微信授权登录仅适用于顾客身份。修改微信头像昵称需先切回顾客态（掌柜登录不会被删除，可重新账号登录），是否立即切换？',
        confirmText: '切回顾客态',
        cancelText: '取消',
        success: (res) => {
          if (!res.confirm) return;
          auth.logoutAdmin();
          app.globalData.isAdmin = false;
          this.refreshUserInfo();
          wx.navigateTo({
            url: '/pages/login/login'
          });
        }
      });
      return;
    }

    wx.navigateTo({
      url: '/pages/login/login'
    });
  },

  // 阻止头像按钮的点击事件冒泡到外层「进入登录页」
  preventBubble() {},

  /**
   * 头像直接上传（open-type="chooseAvatar"）
   * 顾客与掌柜身份都可在个人中心点头像选图 → 上传 → 写回当前账号资料，不再进入登录页
   */
  onChooseAvatar(e) {
    const tempPath = (e && e.detail && e.detail.avatarUrl) || '';
    if (!tempPath) return;

    // 先本地回显，避免等待上传时头像无变化
    this.setData({
      userInfo: { ...(this.data.userInfo || {}), avatarUrl: tempPath }
    });
    wx.showLoading({ title: '头像上传中...', mask: true });

    this.uploadAvatar(tempPath)
      .then(() => {
        wx.hideLoading();
        wx.showToast({ title: '头像已更新', icon: 'success' });
      })
      .catch((err) => {
        wx.hideLoading();
        console.error('头像更新失败:', err);
        wx.showToast({
          title: (err && (err.message || err.msg)) || '头像更新失败',
          icon: 'none'
        });
        // 失败回滚为服务端原头像
        this.refreshUserInfo();
      });
  },

  /** 上传临时头像并写回当前账号资料（顾客/掌柜通用，只改头像，不动登录态） */
  uploadAvatar(tempPath) {
    const token = auth.getActiveToken();
    const currentUser = auth.getUserInfo() || {};

    return request.uploadFile({
      filePath: tempPath,
      name: 'file',
      formData: {
        module: 'avatar',
        userId: currentUser.userId || currentUser.id || ''
      }
    }).then((res) => {
      const avatarPath = (res && (res.relativePath || res.url)) || '';
      if (!avatarPath) throw new Error('头像上传失败');

      // 未登录：顺手完成一次微信静默登录，把头像直接落到新账号上
      if (!token) {
        return auth.login({ avatarUrl: avatarPath }, { forceRole: 'CUSTOMER' });
      }

      // 已登录：严格使用当前身份 Token，确保掌柜改的是自己的账号
      return request.put('/user/profile', { avatarUrl: avatarPath }, {
        header: { Authorization: `Bearer ${token}` }
      });
    }).then((updated) => {
      if (!updated) return;
      const merged = Object.assign({}, auth.getUserInfo() || {}, updated);
      auth.setUserInfo(merged);
      this.setData({
        isLogin: true,
        userInfo: this.formatUser(merged),
        hasProfile: this.checkProfile(merged)
      });
    });
  },

  goToMerchantDashboard() {
    wx.navigateTo({
      url: '/pages/merchant/index/index'
    });
  },

  goToOrders(e) {
    const status = e.currentTarget.dataset.status;
    // 订单页是 tabBar 页，需用 switchTab 并通过 globalData 传递筛选状态
    app.globalData.orderStatusFilter = status;
    wx.switchTab({
      url: '/pages/orders/orders'
    });
  },

  goToHome() {
    wx.switchTab({
      url: '/pages/index/index'
    });
  },

  goToAddressList() {
    wx.navigateTo({
      url: '/pages/address/list/index'
    });
  },

  callShop() {
    const phone = this.data.shopInfo.phone || '0755-88889999';
    wx.makePhoneCall({ phoneNumber: phone });
  },

  viewShopInfo() {
    const shop = this.data.shopInfo;
    if (shop.latitude && shop.longitude) {
      wx.openLocation({
        latitude: Number(shop.latitude),
        longitude: Number(shop.longitude),
        name: shop.name,
        address: shop.address,
        scale: 16
      });
    } else {
      wx.showModal({
        title: shop.name || '维琼鲜肉铺',
        content: `地址: ${shop.address || '未配置'}\n营业时间: ${shop.businessHours || '全天'}\n公告: ${shop.notice || '欢迎光临'}`,
        showCancel: false
      });
    }
  },

  // 退出管理员登录态（平稳切回纯微信顾客态）
  onLogoutAdmin() {
    wx.showModal({
      title: '切换身份',
      content: '确定退出管理员工作模式并切回普通顾客端吗？',
      confirmText: '确定切换',
      cancelText: '取消',
      success: (res) => {
        if (res.confirm) {
          auth.logoutAdmin();
          app.globalData.isAdmin = false;
          this.refreshUserInfo();
          wx.showToast({ title: '已切回顾客身份', icon: 'success' });
        }
      }
    });
  },

  // 退出微信登录态：清空顾客与管理员登录信息，还原首次进入的未登录状态
  onLogoutCustomer() {
    wx.showModal({
      title: '退出登录',
      content: '退出后将清除本机登录状态，重新下单需要再次微信授权，确定退出吗？',
      confirmText: '确定退出',
      confirmColor: '#FF6B6B',
      success: (res) => {
        if (!res.confirm) return;

        // 顾客 + 管理员登录态全部清除（避免管理员 Token 残留导致仍显示已登录）
        auth.clearLoginState();
        // 标记主动退出，重启小程序后也不再自动静默登录
        auth.setLoggedOut(true);
        this.resetToGuest();

        wx.showToast({ title: '已退出登录', icon: 'none' });
      }
    });
  }
});
