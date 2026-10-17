// pages/address/list/index.js
const request = require('../../../utils/request');

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

  loadList() {
    this.setData({ loading: true });
    request.get('/address/list').then((res) => {
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
    request.post(`/address/default/${id}`).then(() => {
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
          request.delete(`/address/${id}`).then(() => {
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
