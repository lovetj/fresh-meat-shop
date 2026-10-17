// pages/checkout/checkout.js
const request = require('../../utils/request');
const auth = require('../../utils/auth');
const { formatImageUrl } = require('../../utils/util');
const app = getApp();

Page({
  data: {
    productId: null,
    shopId: '',
    quantity: 1,
    specInfo: '',
    product: {},
    shopInfo: {},

    // 配送方式: 1-自取, 2-商家配送, 3-外卖(暂不支持)
    deliveryType: 1,

    // 自提信息
    pickupName: '',
    pickupPhone: '',

    // 商家配送收货地址及距离校验
    selectedAddress: null,
    distanceCheckResult: null,
    isSubmitDisabled: false,

    // 支付方式: 1-微信线上支付, 2-货到付款
    payType: 1,

    remark: '',
    goodsAmount: '0.00',
    deliveryFee: '0.00',
    payAmount: '0.00',
    submitting: false,
    // 下单成功弹跳动效
    showSuccess: false
  },

  onLoad(options) {
    const { productId, quantity, specInfo, shopId } = options;
    const user = auth.getUserInfo();
    const targetShopId = shopId || app.globalData.currentShopId || '';

    this.setData({
      productId: Number(productId),
      shopId: targetShopId,
      quantity: Number(quantity) || 1,
      specInfo: decodeURIComponent(specInfo || ''),
      pickupName: (user && user.nickname) || '',
      pickupPhone: (user && user.phone) || ''
    });

    this.loadProduct(productId);
    this.loadShopInfo(targetShopId);
    this.loadDefaultAddress();
  },

  onShow() {
    // 监听可能从地址列表选回的新地址
    const pages = getCurrentPages();
    const currPage = pages[pages.length - 1];
    if (currPage.data.tempSelectedAddress) {
      this.setData({
        selectedAddress: currPage.data.tempSelectedAddress
      });
      delete currPage.data.tempSelectedAddress;
      this.checkAddressDistance(this.data.selectedAddress);
    }
  },

  loadShopInfo(shopId) {
    const params = {};
    if (shopId) {
      params.shopId = shopId;
    }
    request.get('/shop/info', params, { silent: true }).then((res) => {
      if (res) {
        this.setData({
          shopInfo: res,
          shopId: res.id,
          deliveryFee: Number(res.deliveryFee || 0).toFixed(2)
        });
        this.calcTotal();
      }
    });
  },

  loadProduct(id) {
    request.get(`/product/detail/${id}`).then((res) => {
      this.setData({
        product: {
          ...res,
          coverImage: formatImageUrl(res.coverImage)
        }
      });
      if (res.shopId && !this.data.shopId) {
        this.setData({ shopId: res.shopId });
        this.loadShopInfo(res.shopId);
      }
      this.calcTotal();
    });
  },

  loadDefaultAddress() {
    if (!auth.isLoggedIn()) return;
    request.get('/address/default', {}, { silent: true }).then((res) => {
      if (res) {
        this.setData({ selectedAddress: res });
        if (this.data.deliveryType === 2) {
          this.checkAddressDistance(res);
        }
      }
    });
  },

  calcTotal() {
    const { product, quantity, deliveryType, shopInfo } = this.data;
    if (!product.price) return;

    const goods = (Number(product.price) * quantity);
    let fee = 0;
    if (deliveryType === 2) {
      fee = Number(shopInfo.deliveryFee || 0);
    }
    const pay = goods + fee;

    this.setData({
      goodsAmount: goods.toFixed(2),
      deliveryFee: fee.toFixed(2),
      payAmount: pay.toFixed(2)
    });
  },

  onSelectDeliveryType(e) {
    const type = Number(e.currentTarget.dataset.type);
    this.setData({ deliveryType: type }, () => {
      this.calcTotal();
      if (type === 2) {
        if (this.data.selectedAddress) {
          this.checkAddressDistance(this.data.selectedAddress);
        } else {
          this.setData({ isSubmitDisabled: true });
        }
      } else {
        // 自取模式无需校验距离
        this.setData({ isSubmitDisabled: false, distanceCheckResult: null });
      }
    });
  },

  onSelectTakeout() {
    wx.showModal({
      title: '暂未开通',
      content: '第三方外卖配送通道暂未开通，建议您选择「到店自取」或「同城商家专人配送」下单！',
      showCancel: false,
      confirmText: '我知道了'
    });
  },

  chooseAddress() {
    wx.navigateTo({
      url: '/pages/address/list/index?from=checkout'
    });
  },

  checkAddressDistance(address) {
    if (!address || !address.longitude || !address.latitude) {
      this.setData({ isSubmitDisabled: true });
      return;
    }

    wx.showLoading({ title: '测算配送距离...' });
    request.post('/shop/check-distance', {
      longitude: address.longitude,
      latitude: address.latitude,
      shopId: this.data.shopId
    }).then((res) => {
      wx.hideLoading();
      this.setData({
        distanceCheckResult: res,
        isSubmitDisabled: !res.canDeliver
      });
      if (!res.canDeliver) {
        wx.showModal({
          title: '超出配送范围',
          content: res.message,
          confirmText: '切换自取',
          cancelText: '更换地址',
          success: (mRes) => {
            if (mRes.confirm) {
              this.setData({ deliveryType: 1 }, () => {
                this.calcTotal();
                this.setData({ isSubmitDisabled: false });
              });
            } else {
              this.chooseAddress();
            }
          }
        });
      }
    }).catch(() => {
      wx.hideLoading();
    });
  },

  onPayTypeChange(e) {
    this.setData({ payType: Number(e.detail.value) });
  },

  onInputPickupName(e) {
    this.setData({ pickupName: e.detail.value });
  },

  onInputPickupPhone(e) {
    this.setData({ pickupPhone: e.detail.value });
  },

  onInputRemark(e) {
    this.setData({ remark: e.detail.value });
  },

  onSubmitOrder() {
    if (this.data.submitting) return;

    // 1. 登录校验
    if (!auth.isLoggedIn()) {
      wx.showModal({
        title: '提示',
        content: '请先完成微信授权登录后再提交订单',
        confirmText: '去登录',
        success: (res) => {
          if (res.confirm) {
            wx.navigateTo({ url: '/pages/login/login' });
          }
        }
      });
      return;
    }

    // 2. 配送参数校验
    const { deliveryType, pickupName, pickupPhone, selectedAddress, distanceCheckResult, isSubmitDisabled } = this.data;
    if (deliveryType === 1) {
      if (!pickupName.trim()) {
        wx.showToast({ title: '请填写提货人姓名', icon: 'none' });
        return;
      }
      if (!pickupPhone.trim()) {
        wx.showToast({ title: '请填写提货联系电话', icon: 'none' });
        return;
      }
    } else if (deliveryType === 2) {
      if (!selectedAddress) {
        wx.showToast({ title: '请选择收货地址', icon: 'none' });
        return;
      }
      if (isSubmitDisabled || (distanceCheckResult && !distanceCheckResult.canDeliver)) {
        wx.showToast({ title: '超出最大配送范围，无法送达', icon: 'none' });
        return;
      }
    }

    // 3. 构建下单请求 DTO
    const postData = {
      shopId: this.data.shopId,
      deliveryType,
      payType: this.data.payType,
      productId: this.data.productId,
      quantity: this.data.quantity,
      specInfo: this.data.specInfo,
      remark: this.data.remark,
      pickupName: deliveryType === 1 ? pickupName : '',
      pickupPhone: deliveryType === 1 ? pickupPhone : '',
      addressId: deliveryType === 2 ? selectedAddress.id : null
    };

    this.setData({ submitting: true });
    wx.showLoading({ title: '正在提交订单...' });

    request.post('/order/create', postData).then((order) => {
      wx.hideLoading();
      this.setData({ submitting: false });

      if (this.data.payType === 2) {
        // 货到付款：轻量弹跳对勾动效后进入详情
        this.flashSuccess();
        setTimeout(() => {
          wx.redirectTo({
            url: `/pages/order-detail/order-detail?id=${order.id}`
          });
        }, 1500);
      } else {
        // 微信线上支付：调起微信支付流程
        this.processWechatPay(order.id);
      }
    }).catch((err) => {
      wx.hideLoading();
      this.setData({ submitting: false });
    });
  },

  // 下单成功：轻量弹跳 + 对勾动画
  flashSuccess() {
    this.setData({ showSuccess: true });
  },

  /**
   * 微信支付处理逻辑 (支持生产官方签名调起与开发环境 Mock 模拟支付闭环)
   */
  processWechatPay(orderId) {
    wx.showLoading({ title: '唤起微信支付...' });
    request.post(`/payment/wx/prepay/${orderId}`).then((payParams) => {
      wx.hideLoading();

      if (payParams.isMock) {
        // 模拟支付模式
        wx.showModal({
          title: '微信支付 (测试模拟)',
          content: `本次订单应付金额：¥${payParams.payAmount}\n当前为开发体验模式，点击「确认付款」将模拟完成支付并通知肉铺接单。`,
          confirmText: '确认付款',
          cancelText: '稍后付款',
          success: (res) => {
            if (res.confirm) {
              wx.showLoading({ title: '支付处理中...' });
              request.post(`/payment/mock/success/${orderId}`).then(() => {
                wx.hideLoading();
                this.flashSuccess();
                setTimeout(() => {
                  wx.redirectTo({
                    url: `/pages/order-detail/order-detail?id=${orderId}`
                  });
                }, 1200);
              }).catch(() => {
                wx.hideLoading();
              });
            } else {
              // 放弃付款，跳转订单详情为待支付状态
              wx.redirectTo({
                url: `/pages/order-detail/order-detail?id=${orderId}`
              });
            }
          }
        });
      } else {
        // 生产环境调起官方微信原生支付收银台
        wx.requestPayment({
          timeStamp: payParams.timeStamp,
          nonceStr: payParams.nonceStr,
          package: payParams.packageVal,
          signType: payParams.signType || 'RSA',
          paySign: payParams.paySign,
          success: () => {
            this.flashSuccess();
            setTimeout(() => {
              wx.redirectTo({
                url: `/pages/order-detail/order-detail?id=${orderId}`
              });
            }, 1200);
          },
          fail: (payErr) => {
            console.warn('用户取消支付或支付失败:', payErr);
            wx.showToast({ title: '支付未完成', icon: 'none' });
            setTimeout(() => {
              wx.redirectTo({
                url: `/pages/order-detail/order-detail?id=${orderId}`
              });
            }, 1200);
          }
        });
      }
    }).catch((err) => {
      wx.hideLoading();
      wx.redirectTo({
        url: `/pages/order-detail/order-detail?id=${orderId}`
      });
    });
  }
});
