// pages/checkout/checkout.js
const request = require('../../utils/request');
const auth = require('../../utils/auth');
const cart = require('../../utils/cart');
const { formatImageUrl } = require('../../utils/util');
const app = getApp();

Page({
  data: {
    // 是否为购物车多商品下单
    isCartCheckout: false,
    cartItems: [],

    // 单商品直购兼容字段
    productId: null,
    product: {},
    quantity: 1,
    specInfo: '',

    shopId: '',
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
    submitButtonText: '提交订单',

    // 动态配送费与起送价联动
    currentMinOrderAmount: '0.00',
    shortAmount: 0,
    showRulesModal: false,

    // 支付方式: 1-微信线上支付(暂未开通), 2-货到付款
    payType: 2,

    remark: '',
    goodsAmount: '0.00',
    deliveryFee: '0.00',
    payAmount: '0.00',
    submitting: false,
    // 下单成功弹跳动效
    showSuccess: false
  },

  onLoad(options) {
    const { from, productId, quantity, specInfo, shopId } = options;
    const user = auth.getUserInfo();
    const targetShopId = shopId || app.globalData.currentShopId || '';

    this.setData({
      shopId: targetShopId,
      pickupName: (user && user.nickname) || '',
      pickupPhone: (user && user.phone) || ''
    });

    if (from === 'cart') {
      // 购物车批量下单模式
      const cartList = cart.getCart(targetShopId);
      if (!cartList || cartList.length === 0) {
        wx.showToast({ title: '购物车内暂无商品', icon: 'none' });
        setTimeout(() => {
          wx.navigateBack();
        }, 1200);
        return;
      }

      this.setData({
        isCartCheckout: true,
        cartItems: cartList
      });

      this.loadShopInfo(targetShopId);
      this.loadDefaultAddress();
      this.calcTotal();
    } else {
      // 单商品直购模式
      this.setData({
        isCartCheckout: false,
        productId: productId ? String(productId) : '',
        quantity: Number(quantity) || 1,
        specInfo: decodeURIComponent(specInfo || '')
      });

      if (productId) {
        this.loadProduct(productId);
      }
      this.loadShopInfo(targetShopId);
      this.loadDefaultAddress();
    }
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
        const rules = Array.isArray(res.deliveryRules) ? res.deliveryRules : [];
        const firstRule = rules[0] || null;
        let maxDist = 0;
        let hasUnlimited = false;
        rules.forEach(r => {
          if (r.maxDistance === null || r.maxDistance === undefined || Number(r.maxDistance) <= 0) {
            hasUnlimited = true;
          } else if (Number(r.maxDistance) > maxDist) {
            maxDist = Number(r.maxDistance);
          }
        });
        const deliveryRangeText = rules.length === 0
          ? '同城配送'
          : (hasUnlimited ? '同城不限距离' : `限 ${maxDist || 5}km`);

        this.setData({
          shopInfo: {
            ...res,
            deliveryRangeText: deliveryRangeText
          },
          shopId: res.id,
          deliveryFee: Number(firstRule ? firstRule.deliveryFee : 0).toFixed(2),
          currentMinOrderAmount: Number(firstRule ? firstRule.minOrderAmount : 0).toFixed(2)
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
    const { isCartCheckout, cartItems, product, quantity, deliveryType, shopInfo, distanceCheckResult, payType } = this.data;

    let goods = 0;
    if (isCartCheckout) {
      for (const item of cartItems) {
        goods += (Number(item.price) || 0) * (Number(item.quantity) || 1);
      }
    } else {
      if (!product.price) return;
      goods = (Number(product.price) * quantity);
    }

    let fee = 0;
    let minAmt = 0;
    let canDeliver = true;

    if (deliveryType === 2) {
      // 商家配送：动态匹配阶梯费率与起送价
      if (distanceCheckResult) {
        fee = Number(distanceCheckResult.deliveryFee !== undefined ? distanceCheckResult.deliveryFee : 0);
        minAmt = Number(distanceCheckResult.minOrderAmount !== undefined ? distanceCheckResult.minOrderAmount : 0);
        canDeliver = Boolean(distanceCheckResult.canDeliver);
      } else {
        const firstRule = (shopInfo && Array.isArray(shopInfo.deliveryRules) && shopInfo.deliveryRules[0]) || null;
        fee = Number(firstRule ? firstRule.deliveryFee : 0);
        minAmt = Number(firstRule ? firstRule.minOrderAmount : 0);
      }
    }

    const pay = goods + fee;
    const shortAmount = (deliveryType === 2 && goods < minAmt) ? +(minAmt - goods).toFixed(2) : 0;

    // 提交按钮文案与禁用状态精密控制
    let isSubmitDisabled = false;
    let submitButtonText = payType === 1 ? '微信支付下单' : '提交订单';

    if (deliveryType === 2) {
      if (!this.data.selectedAddress) {
        isSubmitDisabled = true;
        submitButtonText = '请选择收货地址';
      } else if (!canDeliver) {
        isSubmitDisabled = true;
        submitButtonText = '超出配送范围';
      } else if (shortAmount > 0) {
        isSubmitDisabled = true;
        submitButtonText = `还差 ¥${shortAmount} 起送`;
      }
    }

    this.setData({
      goodsAmount: goods.toFixed(2),
      deliveryFee: fee.toFixed(2),
      payAmount: pay.toFixed(2),
      currentMinOrderAmount: minAmt.toFixed(2),
      shortAmount,
      isSubmitDisabled,
      submitButtonText
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
          this.setData({
            isSubmitDisabled: true,
            submitButtonText: '请选择收货地址'
          });
        }
      } else {
        // 自取模式无需校验距离
        this.setData({
          isSubmitDisabled: false,
          distanceCheckResult: null,
          submitButtonText: this.data.payType === 1 ? '微信支付下单' : '提交订单'
        });
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
      this.setData({ isSubmitDisabled: true, submitButtonText: '地址缺少经纬度' });
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
        distanceCheckResult: res
      }, () => {
        this.calcTotal();
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

  openRulesModal() {
    this.setData({ showRulesModal: true });
  },

  closeRulesModal() {
    this.setData({ showRulesModal: false });
  },

  onPayTypeChange(e) {
    const val = Number(e.detail.value);
    // 微信线上支付暂未开通，仅支持货到付款
    if (val === 1) {
      this.setData({ payType: 2 }, () => {
        this.calcTotal();
      });
      wx.showToast({ title: '微信支付暂未开通，请选择货到付款', icon: 'none' });
      return;
    }
    this.setData({ payType: val }, () => {
      this.calcTotal();
    });
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
    const { deliveryType, pickupName, pickupPhone, selectedAddress, distanceCheckResult, isSubmitDisabled, shortAmount } = this.data;
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
      if (shortAmount > 0) {
        wx.showToast({ title: `未达到起送金额，还差 ¥${shortAmount} 起送`, icon: 'none' });
        return;
      }
    }

    // 3. 构建下单请求 DTO
    let postData = {
      shopId: this.data.shopId,
      deliveryType,
      payType: this.data.payType,
      remark: this.data.remark,
      pickupName: deliveryType === 1 ? pickupName : '',
      pickupPhone: deliveryType === 1 ? pickupPhone : '',
      addressId: deliveryType === 2 ? selectedAddress.id : null
    };

    if (this.data.isCartCheckout) {
      // 购物车多商品
      postData.items = this.data.cartItems.map(item => ({
        productId: item.productId,
        quantity: item.quantity,
        specInfo: item.specInfo
      }));
    } else {
      // 单商品
      postData.productId = this.data.productId;
      postData.quantity = this.data.quantity;
      postData.specInfo = this.data.specInfo;
    }

    this.setData({ submitting: true });
    wx.showLoading({ title: '正在提交订单...' });

    request.post('/order/create', postData).then((order) => {
      wx.hideLoading();
      this.setData({ submitting: false });

      // 下单成功：若是购物车下单，自动清理该门店购物车缓存
      if (this.data.isCartCheckout) {
        cart.clearCart(this.data.shopId);
      }

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
    wx.showLoading({ title: '调起支付...' });
    request.post('/payment/wx/create-params', { orderId }).then((payParams) => {
      wx.hideLoading();

      // 判断是否走 Mock 模式（当后端未配置微信商户证书或处于沙箱开发状态时）
      if (payParams && payParams.mock) {
        wx.showModal({
          title: '微信沙箱模拟支付',
          content: `正在模拟支付订单金额: ¥${this.data.payAmount}，点击确认立即完成支付！`,
          confirmText: '确认支付',
          cancelText: '取消',
          success: (res) => {
            if (res.confirm) {
              wx.showLoading({ title: '确认扣款中...' });
              request.post(`/payment/wx/mock-notify/${orderId}`).then(() => {
                wx.hideLoading();
                this.flashSuccess();
                setTimeout(() => {
                  wx.redirectTo({
                    url: `/pages/order-detail/order-detail?id=${orderId}`
                  });
                }, 1500);
              }).catch(() => {
                wx.hideLoading();
                wx.redirectTo({
                  url: `/pages/order-detail/order-detail?id=${orderId}`
                });
              });
            } else {
              wx.redirectTo({
                url: `/pages/order-detail/order-detail?id=${orderId}`
              });
            }
          }
        });
      } else {
        // 真实微信支付小程序拉起
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
            }, 1500);
          },
          fail: (err) => {
            wx.showToast({ title: '取消或支付未完成', icon: 'none' });
            setTimeout(() => {
              wx.redirectTo({
                url: `/pages/order-detail/order-detail?id=${orderId}`
              });
            }, 1200);
          }
        });
      }
    }).catch(() => {
      wx.hideLoading();
    });
  }
});
