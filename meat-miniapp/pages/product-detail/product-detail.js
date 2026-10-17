// pages/product-detail/product-detail.js
const app = getApp();
const request = require('../../utils/request');
const { formatImageUrl } = require('../../utils/util');

Page({
  data: {
    productId: null,
    product: null,
    specGroups: [],
    selectedSpecs: [],
    buyQuantity: 1,
    totalPrice: '0.00'
  },

  onLoad(options) {
    if (options.id) {
      this.setData({ productId: options.id });
      this.loadDetail(options.id);
    }
  },

  loadDetail(id) {
    wx.showLoading({ title: '加载中...' });
    request.get(`/product/detail/${id}`).then((res) => {
      wx.hideLoading();
      let specGroups = [];
      try {
        if (res.specsJson) {
          specGroups = typeof res.specsJson === 'string' ? JSON.parse(res.specsJson) : res.specsJson;
        }
      } catch (e) {
        specGroups = [];
      }

      const selectedSpecs = specGroups.map(g => (g.options && g.options.length ? g.options[0] : ''));
      const totalPrice = (Number(res.price) * this.data.buyQuantity).toFixed(2);

      this.setData({
        product: {
          ...res,
          coverImage: formatImageUrl(res.coverImage)
        },
        specGroups,
        selectedSpecs,
        totalPrice
      });
    }).catch(() => {
      wx.hideLoading();
    });
  },

  onSelectOption(e) {
    const { gIndex, opt } = e.currentTarget.dataset;
    const selectedSpecs = [...this.data.selectedSpecs];
    selectedSpecs[gIndex] = opt;
    this.setData({ selectedSpecs });
  },

  onDecQuantity() {
    if (this.data.buyQuantity <= 1) return;
    const buyQuantity = this.data.buyQuantity - 1;
    const totalPrice = (Number(this.data.product.price) * buyQuantity).toFixed(2);
    this.setData({ buyQuantity, totalPrice });
  },

  onIncQuantity() {
    if (this.data.buyQuantity >= this.data.product.stock) {
      wx.showToast({ title: '库存不足', icon: 'none' });
      return;
    }
    const buyQuantity = this.data.buyQuantity + 1;
    const totalPrice = (Number(this.data.product.price) * buyQuantity).toFixed(2);
    this.setData({ buyQuantity, totalPrice });
  },

  callShop() {
    const shop = app.globalData.shopInfo;
    const phone = (shop && shop.phone) || '13800138000';
    wx.makePhoneCall({ phoneNumber: phone });
  },

  goToCheckout() {
    const { product, buyQuantity, selectedSpecs } = this.data;
    const specInfo = selectedSpecs.filter(Boolean).join(' / ') || (product && product.unit) || '';
    const shopId = (product && product.shopId) || app.globalData.currentShopId || '';

    wx.navigateTo({
      url: `/pages/checkout/checkout?productId=${product.id}&quantity=${buyQuantity}&specInfo=${encodeURIComponent(specInfo)}&shopId=${shopId}`
    });
  }
});
