/**
 * 微信授权登录页
 * 链路：chooseAvatar 原生头像授权 → 自动唤起微信昵称气泡 → 回显头像/昵称 → 自动登录
 * 头像先上传至 /file/upload 得到相对路径，再随 wx.login 的 code 一起提交 /user/wx/login
 */
const auth = require('../../utils/auth');
const request = require('../../utils/request');
const { formatImageUrl } = require('../../utils/config');

const app = getApp();

// 后端新用户默认昵称，视为"未完善资料"
const DEFAULT_NICKNAME = '微信顾客';

/** 判断是否为本地临时文件路径 */
function isTempPath(path) {
  if (!path) return false;
  return (
    path.indexOf('wxfile://') === 0 ||
    path.indexOf('http://tmp') === 0 ||
    path.indexOf('https://tmp') === 0 ||
    path.indexOf('tmp/') === 0
  );
}

Page({
  data: {
    avatarUrl: '',        // 页面回显用（临时路径或已保存的完整 URL）
    localAvatarPath: '',  // 本次新选择的临时头像，需要上传
    nickName: '',
    nicknameFocus: false,
    agreed: true,
    loading: false,
    isUpdate: false,
    // 掌柜管理员独立登录弹窗状态
    showAdminModal: false,
    submittingAdmin: false,
    // 密码默认密文，点击「显示」切换为明文
    showAdminPwd: false,
    // 密码框聚焦标记：首次点击弹窗内密码区时主动聚焦，避免原生 input 命中区错位导致第一次点击无效
    adminPwdFocus: false,
    adminForm: {
      username: '',
      password: ''
    }
  },

  onLoad(options) {
    // 仅在当前为顾客身份时回填资料，避免把掌柜信息带到顾客授权流程
    const user = auth.getActiveRole() === 'CUSTOMER' ? (auth.getUserInfo() || {}) : {};
    const nickName = user.nickname && user.nickname !== DEFAULT_NICKNAME ? user.nickname : '';
    const avatarUrl = user.avatarUrl ? formatImageUrl(user.avatarUrl) : '';

    this.setData({
      nickName,
      avatarUrl,
      isUpdate: !!(nickName || avatarUrl)
    });

    // 掌柜登录态失效 / 被顶下线后跳转过来，直接弹出账号密码登录框
    if (options && (options.admin === '1' || options.admin === 1)) {
      setTimeout(() => this.openAdminModal(), 300);
    }
  },

  /**
   * 微信头像授权回调（open-type="chooseAvatar"）
   * 1. 回显头像并记录临时路径
   * 2. 昵称未授权则自动唤起微信昵称气泡，否则直接登录
   */
  onChooseAvatar(e) {
    const avatarUrl = (e.detail && e.detail.avatarUrl) || '';
    if (!avatarUrl) return;

    this.setData({
      avatarUrl,
      localAvatarPath: avatarUrl
    });

    if (!(this.data.nickName || '').trim()) {
      this.focusNicknameInput();
    } else {
      this.doLogin();
    }
  },

  /**
   * 唤起微信昵称气泡（type="nickname" 输入框聚焦后键盘上方自动弹出）
   * 先置 false 再延迟置 true，保证连续触发时 focus 依然有效
   */
  focusNicknameInput() {
    this.setData({ nicknameFocus: false }, () => {
      setTimeout(() => {
        this.setData({ nicknameFocus: true });
      }, 300);
    });
  },

  onNicknameInput(e) {
    const nickName = (e && e.detail && e.detail.value) || '';
    if (nickName) {
      this.setData({ nickName });
    }
  },

  onNicknameBlur(e) {
    const nickName = (e && e.detail && e.detail.value) ? String(e.detail.value).trim() : '';
    this.setData({ nicknameFocus: false });
    if (nickName) {
      this.setData({ nickName });
      this.tryAutoLogin();
    }
  },

  onToggleAgree() {
    this.setData({ agreed: !this.data.agreed });
  },

  /** 头像、昵称均已授权时自动登录 */
  tryAutoLogin() {
    const hasAvatar = !!this.data.avatarUrl;
    const hasNickname = !!(this.data.nickName || '').trim();
    if (hasAvatar && hasNickname && !this.data.loading) {
      this.doLogin();
    }
  },

  onLogin() {
    if (!this.data.agreed) {
      wx.showToast({ title: '请先阅读并同意用户协议', icon: 'none' });
      return;
    }
    if (this.data.loading) return;

    const hasAvatar = !!this.data.avatarUrl;
    const hasNickname = !!(this.data.nickName || '').trim();

    if (!hasAvatar) {
      // 按钮 open-type="chooseAvatar"，微信头像选择器即将弹出
      return;
    }
    if (!hasNickname) {
      wx.showToast({ title: '请点击键盘上方的微信昵称完成授权', icon: 'none' });
      this.focusNicknameInput();
      return;
    }
    this.doLogin();
  },

  /** 上传临时头像，返回后端存储的相对路径 */
  uploadAvatar(tempPath) {
    if (!isTempPath(tempPath)) {
      return Promise.resolve('');
    }
    const user = auth.getCustomerUserInfo() || {};
    return request.uploadFile({
      filePath: tempPath,
      name: 'file',
      formData: {
        module: 'avatar',
        userId: user.id || ''
      }
    }).then((res) => {
      if (!res) return '';
      return res.relativePath || res.url || '';
    });
  },

  /**
   * 掌柜态下的身份切换保护
   * 微信授权登录会强制切换为顾客身份（并清除管理员 Token），
   * 必须先让用户确认，避免「编辑资料/去结算」时把掌柜登录态顶掉。
   */
  confirmSwitchFromAdmin() {
    if (!auth.isAdmin()) return Promise.resolve(true);
    return new Promise((resolve) => {
      wx.showModal({
        title: '身份切换提示',
        content: '当前处于掌柜管理员登录态，微信授权登录会切换为顾客身份并退出掌柜模式（可重新账号登录）。是否继续？',
        confirmText: '继续登录',
        cancelText: '取消',
        success: (res) => resolve(!!res.confirm),
        fail: () => resolve(false)
      });
    });
  },

  async doLogin() {
    if (this.data.loading) return;
    if (!this.data.agreed) {
      wx.showToast({ title: '请先阅读并同意用户协议', icon: 'none' });
      return;
    }

    // 掌柜身份下先确认身份切换，取消则不改变任何登录态
    const canSwitch = await this.confirmSwitchFromAdmin();
    if (!canSwitch) return;

    this.setData({ loading: true });
    wx.showLoading({ title: '登录中...', mask: true });

    try {
      // 1. 头像上传（失败不阻断登录，仅同步昵称）
      let avatarPath = '';
      if (isTempPath(this.data.localAvatarPath)) {
        try {
          avatarPath = await this.uploadAvatar(this.data.localAvatarPath);
        } catch (uploadErr) {
          console.warn('头像上传失败，本次仅同步昵称:', uploadErr);
        }
      }

      // 2. wx.login 换取凭证 + 提交微信授权资料（后端已登录用户会被覆盖更新）
      const nickname = (this.data.nickName || '').trim();
      // forceRole：显式微信登录一律切回「顾客」身份，个人中心展示顾客端样式
      const user = await auth.login(
        { nickname, avatarUrl: avatarPath },
        { forceRole: 'CUSTOMER' }
      );

      // 3. 登录成功后清除残留的掌柜登录态，避免个人中心仍出现管理员样式
      if (auth.getAdminToken()) {
        auth.logoutAdmin();
      }

      wx.hideLoading();
      this.setData({ loading: false });
      this.handleLoginSuccess(user);
    } catch (err) {
      wx.hideLoading();
      this.setData({ loading: false });
      console.error('微信授权登录失败:', err);

      const msg = (err && (err.message || err.msg)) || '登录失败，请稍后重试';
      wx.showModal({
        title: '登录提示',
        content: msg,
        showCancel: false,
        confirmColor: '#FF6B6B'
      });
    }
  },

  handleLoginSuccess(user) {
    app.globalData.userInfo = user || null;
    app.globalData.isAdmin = false;

    wx.showToast({
      title: this.data.isUpdate ? '资料已更新' : '登录成功',
      icon: 'success',
      duration: 900
    });

    setTimeout(() => {
      this.back();
    }, 900);
  },

  back() {
    const pages = getCurrentPages();
    if (pages.length > 1) {
      wx.navigateBack();
    } else {
      wx.switchTab({ url: '/pages/index/index' });
    }
  },

  onSkip() {
    this.back();
  },

  // ================= 掌柜管理员专属登录逻辑 =================
  openAdminModal() {
    this.setData({
      showAdminModal: true,
      showAdminPwd: false,
      adminPwdFocus: false,
      'adminForm.username': '',
      'adminForm.password': ''
    });
  },

  closeAdminModal() {
    this.setData({
      showAdminModal: false,
      submittingAdmin: false,
      showAdminPwd: false,
      adminPwdFocus: false
    });
  },

  /**
   * 点击密码框区域时主动聚焦：
   * 弹窗内的原生 input 在弹入动画/父级 transform 期间首次点击可能拿不到焦点，
   * 由外层容器兜底聚焦，保证第一次点击就能唤起键盘并定位光标
   */
  focusAdminPwd() {
    if (this.data.adminPwdFocus) {
      // 已处于聚焦态时再点一次无需重复 setData（真机重复 setData 会重置光标）
      return;
    }
    this.setData({ adminPwdFocus: true });
  },

  onAdminPwdBlur() {
    this.setData({ adminPwdFocus: false });
  },

  // 密码明文 / 密文切换
  toggleAdminPwd() {
    this.setData({ showAdminPwd: !this.data.showAdminPwd });
  },

  preventBubble() {},

  onInputUsername(e) {
    this.setData({
      'adminForm.username': e.detail.value
    });
  },

  onInputPassword(e) {
    this.setData({
      'adminForm.password': e.detail.value
    });
  },

  submitAdminLogin() {
    const { username, password } = this.data.adminForm;
    if (!username || !username.trim()) {
      return wx.showToast({ title: '请输入管理账号', icon: 'none' });
    }
    if (!password || !password.trim()) {
      return wx.showToast({ title: '请输入管理密码', icon: 'none' });
    }

    this.setData({ submittingAdmin: true });
    wx.showLoading({ title: '正在鉴权...', mask: true });

    auth.loginAdmin(username, password).then((res) => {
      wx.hideLoading();
      app.globalData.userInfo = res;
      app.globalData.isAdmin = true;
      this.setData({
        submittingAdmin: false,
        showAdminModal: false,
        showAdminPwd: false,
        'adminForm.password': ''
      });

      wx.showToast({
        title: `欢迎回来，${res.nickname || '掌柜'}`,
        icon: 'success',
        duration: 1000
      });

      // 登录成功后返回个人中心，掌柜工作台入口在个人中心展示
      setTimeout(() => {
        wx.switchTab({ url: '/pages/mine/mine' });
      }, 1000);
    }).catch((err) => {
      wx.hideLoading();
      this.setData({ submittingAdmin: false });
      wx.showToast({
        title: err.message || '账号或密码错误',
        icon: 'none',
        duration: 2500
      });
    });
  }
});
