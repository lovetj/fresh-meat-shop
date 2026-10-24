// pages/order-detail/order-detail.js
const request = require('../../utils/request');
const auth = require('../../utils/auth');
const { formatImageUrl } = require('../../utils/util');

Page({
  data: {
    orderId: null,
    isAdmin: false,
    order: {},
    // 是否存在可执行的操作按钮（无按钮时不渲染底部悬浮栏）
    hasActions: false,

    // 改价弹窗
    showPriceModal: false,
    newPayAmount: '',
    priceReason: '',

    // 快递发货弹窗（掌柜端）
    showExpressModal: false,
    expressCompany: '',
    expressNo: '',
    expressCompanies: ['顺丰速运', '京东物流', '中通快递', '圆通速递', '申通快递', '韵达快递', '极兔速递', '邮政EMS'],

    // 顾客端：根据快递公司推荐查询物流的 App 提示
    expressTipText: ''
  },

  onLoad(options) {
    const isAdmin = options && (options.isAdmin === '1' || options.isAdmin === 'true');
    this.setData({
      isAdmin: !!isAdmin && auth.isAdmin()
    });
    if (options.id) {
      this.setData({ orderId: options.id });
      this.loadOrderDetail(options.id);
    }
  },

  onPullDownRefresh() {
    if (this.data.orderId) {
      this.loadOrderDetail(this.data.orderId).then(() => {
        wx.stopPullDownRefresh();
      });
    }
  },

  /**
   * 当前生效身份（顾客/掌柜）对应的请求头
   * 订单详情查询与后续操作必须使用当前登录用户的 Token，避免串号
   */
  authHeader() {
    const token = auth.getActiveToken();
    return token ? { header: { Authorization: `Bearer ${token}` } } : {};
  },

  loadOrderDetail(id) {
    wx.showLoading({ title: '加载中...' });
    return request.get(`/order/detail/${id}`, {}, this.authHeader()).then((res) => {
      wx.hideLoading();
      if (res && res.items) {
        res.items = res.items.map((item) => ({
          ...item,
          productImage: formatImageUrl(item.productImage)
        }));
      }
      this.setData({
        order: res,
        hasActions: this.resolveHasActions(res),
        expressTipText: this.resolveExpressTip(res && res.expressCompany)
      });
    }).catch(() => {
      wx.hideLoading();
    });
  },

  /**
   * 判断当前身份下是否存在可执行操作：
   * 掌柜端仅在 待支付(0)/待接单(1)/待配送(2)/配送中(3) 有按钮；
   * 顾客端由后端返回的 canPay / canCancel / canConfirm 决定
   */
  resolveHasActions(order) {
    if (!order || order.status === undefined || order.status === null) return false;
    if (this.data.isAdmin) {
      return order.status >= 0 && order.status <= 3;
    }
    return !!(order.canPay || order.canCancel || order.canConfirm);
  },

  /** 根据快递公司名称推荐查询物流的 App */
  resolveExpressApp(company) {
    const name = (company || '').trim();
    if (!name) return '菜鸟';
    if (name.includes('顺丰')) return '顺丰速运';
    if (name.includes('京东')) return '京东';
    if (name.includes('邮政') || /ems/i.test(name)) return '中国邮政';
    if (name.includes('德邦')) return '德邦快递';
    if (name.includes('中通')) return '中通快递';
    if (name.includes('圆通')) return '圆通速递';
    if (name.includes('申通')) return '申通快递';
    if (name.includes('韵达')) return '韵达快递';
    if (name.includes('极兔')) return '极兔速递';
    return '菜鸟';
  },

  /** 顾客端物流查询文案：未识别快递公司时统一引导使用「菜鸟」App */
  resolveExpressTip(company) {
    const app = this.resolveExpressApp(company);
    if (app === '菜鸟') {
      return '打开「菜鸟」App，粘贴快递单号即可查询物流进度';
    }
    return `打开「${app}」App 或小程序查询物流，也可在「菜鸟」App 中粘贴单号查询`;
  },

  copyOrderNo() {
    wx.setClipboardData({
      data: this.data.order.orderNo,
      success: () => {
        wx.showToast({ title: '已复制订单号', icon: 'none' });
      }
    });
  },

  makePhoneCall(e) {
    const phone = e.currentTarget.dataset.phone || this.data.order.receiverPhone;
    if (phone) {
      wx.makePhoneCall({ phoneNumber: phone });
    }
  },

  /** 复制快递单号，方便顾客到快递 App 粘贴查询 */
  copyExpressNo() {
    const expressNo = (this.data.order && this.data.order.expressNo) || '';
    if (!expressNo) return;
    wx.setClipboardData({
      data: expressNo,
      success: () => {
        wx.showToast({ title: '快递单号已复制', icon: 'none' });
      }
    });
  },

  /**
   * 顾客端自提：导航到提货门店
   */
  openShopLocation() {
    const order = this.data.order || {};
    const latitude = Number(order.shopLat);
    const longitude = Number(order.shopLng);
    if (!latitude || !longitude) {
      wx.showToast({ title: '门店暂未配置位置', icon: 'none' });
      return;
    }
    wx.openLocation({
      latitude,
      longitude,
      name: order.shopName || '提货门店',
      address: order.shopAddress || '',
      scale: 18
    });
  },

  /**
   * 掌柜端商家配送：导航到顾客收货地址
   */
  openReceiverLocation() {
    const order = this.data.order || {};
    const latitude = Number(order.receiverLat);
    const longitude = Number(order.receiverLng);
    if (!latitude || !longitude) {
      wx.showToast({ title: '顾客地址未包含定位信息', icon: 'none' });
      return;
    }
    wx.openLocation({
      latitude,
      longitude,
      name: `${order.receiverName || '顾客'}的收货地址`,
      address: order.receiverAddress || '',
      scale: 18
    });
  },

  preventBubble() {},

  // ================= 顾客端操作 =================
  /**
   * 订单当前不允许取消（商家已接单/配送中/已完成等）时的明确提示，
   * 用户确认后同步最新订单状态，避免页面停留在旧状态、按钮误判
   */
  showCancelNotAllowed(message) {
    wx.showModal({
      title: '无法取消订单',
      content: message || '当前订单状态不可取消',
      showCancel: false,
      confirmText: '我知道了',
      confirmColor: '#FF6B6B',
      success: () => {
        if (this.data.orderId) {
          this.loadOrderDetail(this.data.orderId);
        }
      }
    });
  },

  onCancelOrder() {
    const order = this.data.order || {};
    // 与后端校验保持一致：仅待支付(0)/待接单(1)可取消，商家接单后不再允许取消
    if (order.canCancel !== true) {
      this.showCancelNotAllowed('商家已接单或订单状态已变更，当前订单不可取消');
      return;
    }

    wx.showModal({
      title: '取消确认',
      content: '确定要取消此鲜肉订单吗？若已付款将自动原路退款。',
      success: (res) => {
        if (!res.confirm) return;
        wx.showLoading({ title: '处理中...' });
        // silent: true 由本页统一处理失败提示，避免被 hideLoading 吞掉
        request.post(`/order/cancel/${this.data.orderId}`, { reason: '顾客取消' }, { ...this.authHeader(), silent: true }).then(() => {
          wx.hideLoading();
          wx.showToast({ title: '已取消', icon: 'success' });
          this.loadOrderDetail(this.data.orderId);
        }).catch((err) => {
          wx.hideLoading();
          this.showCancelNotAllowed((err && err.message) || '当前订单状态不可取消');
        });
      }
    });
  },

  onPayOrder() {
    const id = this.data.orderId;
    wx.showLoading({ title: '拉取支付...' });
    request.post(`/payment/wx/prepay/${id}`, {}, this.authHeader()).then((payParams) => {
      wx.hideLoading();
      if (payParams.isMock) {
        wx.showModal({
          title: '微信支付 (测试模拟)',
          content: `应付金额：¥${payParams.payAmount}\n确认完成模拟付款？`,
          confirmText: '确认付款',
          success: (mRes) => {
            if (mRes.confirm) {
              request.post(`/payment/mock/success/${id}`, {}, this.authHeader()).then(() => {
                wx.showToast({ title: '支付成功！', icon: 'success' });
                this.loadOrderDetail(id);
              });
            }
          }
        });
      } else {
        wx.requestPayment({
          timeStamp: payParams.timeStamp,
          nonceStr: payParams.nonceStr,
          package: payParams.packageVal,
          signType: payParams.signType || 'RSA',
          paySign: payParams.paySign,
          success: () => {
            wx.showToast({ title: '支付成功', icon: 'success' });
            this.loadOrderDetail(id);
          }
        });
      }
    }).catch(() => {
      wx.hideLoading();
    });
  },

  onConfirmOrder() {
    wx.showModal({
      title: '确认收货',
      content: '确认已收到新鲜肉品？',
      success: (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '确认中...' });
          request.post(`/order/confirm/${this.data.orderId}`, {}, this.authHeader()).then(() => {
            wx.hideLoading();
            wx.showToast({ title: '订单已完成！', icon: 'success' });
            this.loadOrderDetail(this.data.orderId);
          }).catch(() => {
            wx.hideLoading();
          });
        }
      }
    });
  },

  // ================= 掌柜端操作 =================
  onAcceptOrder() {
    const id = this.data.orderId;
    wx.showModal({
      title: '确认接单',
      content: '确认接下此鲜肉订单并开始切配？',
      success: (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '处理中...' });
          request.post(`/order/admin/accept/${id}`, {}, this.authHeader())
            .then(() => {
              wx.showToast({ title: '已成功接单', icon: 'success' });
              this.loadOrderDetail(id);
            })
            .catch(err => {
              wx.showToast({ title: err.message || '接单失败', icon: 'none' });
            })
            .finally(() => {
              wx.hideLoading();
            });
        }
      }
    });
  },

  onRejectOrder() {
    const id = this.data.orderId;
    wx.showModal({
      title: '拒单确认',
      editable: true,
      placeholderText: '请输入拒单原因（如：当日肉品售罄）',
      success: (res) => {
        if (res.confirm) {
          const reason = res.content ? res.content.trim() : '商家暂时无法接单';
          wx.showLoading({ title: '处理中...' });
          request.post(`/order/admin/reject/${id}`, { reason: reason }, this.authHeader())
            .then(() => {
              wx.showToast({ title: '已拒单并原路退款', icon: 'success' });
              this.loadOrderDetail(id);
            })
            .catch(err => {
              wx.showToast({ title: err.message || '拒单失败', icon: 'none' });
            })
            .finally(() => {
              wx.hideLoading();
            });
        }
      }
    });
  },

  onDeliverOrder() {
    const id = this.data.orderId;
    const type = this.data.order.deliveryType;
    const actionText = type === 1 ? '通知备齐可自提' : '开始同城配送';

    wx.showModal({
      title: '操作确认',
      content: `确认将此订单状态更新为【${actionText}】？`,
      success: (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '更新中...' });
          request.post(`/order/admin/deliver/${id}`, {}, this.authHeader())
            .then(() => {
              wx.showToast({ title: '已更新状态', icon: 'success' });
              this.loadOrderDetail(id);
            })
            .catch(err => {
              wx.showToast({ title: err.message || '更新失败', icon: 'none' });
            })
            .finally(() => {
              wx.hideLoading();
            });
        }
      }
    });
  },

  onFinishOrder() {
    const id = this.data.orderId;
    const type = this.data.order.deliveryType;
    const tip = type === 1 ? '确认顾客已到店提走鲜肉？' : '确认该笔鲜肉订单已配送送达？';

    wx.showModal({
      title: '完成确认',
      content: tip,
      success: (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '处理中...' });
          request.post(`/order/admin/finish/${id}`, {}, this.authHeader())
            .then(() => {
              wx.showToast({ title: '订单已完成', icon: 'success' });
              this.loadOrderDetail(id);
            })
            .catch(err => {
              wx.showToast({ title: err.message || '操作失败', icon: 'none' });
            })
            .finally(() => {
              wx.hideLoading();
            });
        }
      }
    });
  },

  onAdminCancelOrder() {
    const id = this.data.orderId;
    wx.showModal({
      title: '掌柜取消并退款',
      editable: true,
      placeholderText: '请输入取消退款原因',
      success: (res) => {
        if (res.confirm) {
          const reason = res.content ? res.content.trim() : '掌柜协商取消退款';
          wx.showLoading({ title: '处理中...' });
          request.post(`/order/admin/cancel/${id}`, { reason: reason }, this.authHeader())
            .then(() => {
              wx.showToast({ title: '已取消退款', icon: 'success' });
              this.loadOrderDetail(id);
            })
            .catch(err => {
              wx.showToast({ title: err.message || '取消失败', icon: 'none' });
            })
            .finally(() => {
              wx.hideLoading();
            });
        }
      }
    });
  },

  openPriceModal() {
    this.setData({
      showPriceModal: true,
      newPayAmount: this.data.order.payAmount,
      priceReason: ''
    });
  },

  closePriceModal() {
    this.setData({
      showPriceModal: false,
      newPayAmount: '',
      priceReason: ''
    });
  },

  onPriceInput(e) {
    this.setData({ newPayAmount: e.detail.value });
  },

  onPriceReasonInput(e) {
    this.setData({ priceReason: e.detail.value });
  },

  confirmChangePrice() {
    const amount = parseFloat(this.data.newPayAmount);
    if (isNaN(amount) || amount <= 0) {
      wx.showToast({ title: '请输入有效金额', icon: 'none' });
      return;
    }
    wx.showLoading({ title: '提交中...' });
    request.post(`/order/admin/change-price/${this.data.orderId}`, {
      newPayAmount: amount,
      reason: this.data.priceReason.trim()
    }, this.authHeader()).then(() => {
      wx.hideLoading();
      wx.showToast({ title: '改价成功', icon: 'success' });
      this.closePriceModal();
      this.loadOrderDetail(this.data.orderId);
    }).catch(err => {
      wx.hideLoading();
      wx.showToast({ title: err.message || '改价失败', icon: 'none' });
    });
  },

  // ================= 快递配送发货（掌柜端） =================
  openExpressModal() {
    this.setData({
      showExpressModal: true,
      expressCompany: '',
      expressNo: ''
    });
  },

  closeExpressModal() {
    this.setData({
      showExpressModal: false,
      expressCompany: '',
      expressNo: ''
    });
  },

  onExpressCompanyInput(e) {
    this.setData({ expressCompany: e.detail.value });
  },

  onSelectExpressCompany(e) {
    this.setData({ expressCompany: e.currentTarget.dataset.company });
  },

  onExpressNoInput(e) {
    this.setData({ expressNo: e.detail.value });
  },

  /** 提交快递发货：成功后订单流转为「快递运输中(3)」 */
  confirmExpressDeliver() {
    const expressCompany = (this.data.expressCompany || '').trim();
    const expressNo = (this.data.expressNo || '').trim();
    if (!expressCompany) {
      wx.showToast({ title: '请填写快递公司', icon: 'none' });
      return;
    }
    if (!expressNo) {
      wx.showToast({ title: '请填写快递单号', icon: 'none' });
      return;
    }

    wx.showLoading({ title: '提交中...' });
    request.post(`/order/admin/express/${this.data.orderId}`, {
      expressCompany,
      expressNo
    }, this.authHeader()).then(() => {
      wx.hideLoading();
      this.closeExpressModal();
      wx.showToast({ title: '快递已发出', icon: 'success' });
      this.loadOrderDetail(this.data.orderId);
    }).catch((err) => {
      wx.hideLoading();
      wx.showToast({ title: (err && err.message) || '发货失败', icon: 'none' });
    });
  }
});
