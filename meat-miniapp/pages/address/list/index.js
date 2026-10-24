// pages/address/list/index.js
const request = require('../../../utils/request');
const auth = require('../../../utils/auth');

Page({
  data: {
    addressList: [],
    loading: false,
    from: ''
  },

  onLoad(options) {
    if (options.from) {
      this.setData({ from: options.from });
    }
  },

  onShow() {
    this.loadList();
  },

  /**
   * 按「当前生效身份」取 Token：
   * 掌柜身份下读写的是掌柜本人的地址，顾客身份下读写顾客本人的地址，
   * 后端再按该 Token 的 user_id 过滤，避免两种身份互相串号
   */
  authHeader() {
    const token = auth.getActiveToken();
    return token ? { header: { Authorization: `Bearer ${token}` } } : {};
  },

  loadList() {
    this.setData({ loading: true });
    request.get('/address/list', {}, this.authHeader()).then((res) => {
      this.setData({
        addressList: res || [],
        loading: false
      });
    }).catch(() => {
      this.setData({ loading: false });
    });
  },

  onSelectAddress(e) {
    if (this.data.from === 'checkout') {
      const item = e.currentTarget.dataset.item;
      const pages = getCurrentPages();
      const prevPage = pages[pages.length - 2];
      if (prevPage) {
        prevPage.setData({
          tempSelectedAddress: item
        });
      }
      wx.navigateBack();
    }
  },

  preventBubble() {},

  onSetDefault(e) {
    const id = e.currentTarget.dataset.id;
    wx.showLoading({ title: '设置中...' });
    request.post(`/address/default/${id}`, {}, this.authHeader()).then(() => {
      wx.hideLoading();
      this.loadList();
    }).catch(() => {
      wx.hideLoading();
    });
  },

  onAddAddress() {
    wx.navigateTo({
      url: '/pages/address/edit/index'
    });
  },

  onEditAddress(e) {
    const item = e.currentTarget.dataset.item;
    wx.navigateTo({
      url: `/pages/address/edit/index?data=${encodeURIComponent(JSON.stringify(item))}`
    });
  },

  onDeleteAddress(e) {
    const id = e.currentTarget.dataset.id;
    wx.showModal({
      title: '删除提示',
      content: '确定要删除该收货地址吗？',
      success: (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '删除中...' });
          request.delete(`/address/${id}`, {}, this.authHeader()).then(() => {
            wx.hideLoading();
            this.loadList();
          }).catch(() => {
            wx.hideLoading();
          });
        }
      }
    });
  }
});
