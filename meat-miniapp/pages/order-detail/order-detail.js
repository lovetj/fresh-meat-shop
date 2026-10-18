// pages/order-detail/order-detail.js
const request = require('../../utils/request');
const auth = require('../../utils/auth');
const { formatImageUrl } = require('../../utils/util');

Page({
  data: {
    orderId: null,
    isAdmin: false,
    order: {},

    // 改价弹窗
    showPriceModal: false,
    newPayAmount: '',
    priceReason: ''
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

  loadOrderDetail(id) {
    wx.showLoading({ title: '加载中...' });
    return request.get(`/order/detail/${id}`).then((res) => {
      wx.hideLoading();
      if (res && res.items) {
        res.items = res.items.map((item) => ({
          ...item,
          productImage: formatImageUrl(item.productImage)
        }));
      }
      this.setData({ order: res });
    }).catch(() => {
      wx.hideLoading();
    });
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

  preventBubble() {},

  // ================= 顾客端操作 =================
  onCancelOrder() {
    wx.showModal({
      title: '取消确认',
      content: '确定要取消此鲜肉订单吗？若已付款将自动原路退款。',
      success: (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '处理中...' });
          request.post(`/order/cancel/${this.data.orderId}`, { reason: '顾客取消' }).then(() => {
            wx.hideLoading();
            wx.showToast({ title: '已取消', icon: 'success' });
            this.loadOrderDetail(this.data.orderId);
          }).catch(() => {
            wx.hideLoading();
          });
        }
      }
    });
  },

  onPayOrder() {
    const id = this.data.orderId;
    wx.showLoading({ title: '拉取支付...' });
    request.post(`/payment/wx/prepay/${id}`).then((payParams) => {
      wx.hideLoading();
      if (payParams.isMock) {
        wx.showModal({
          title: '微信支付 (测试模拟)',
          content: `应付金额：¥${payParams.payAmount}\n确认完成模拟付款？`,
          confirmText: '确认付款',
          success: (mRes) => {
            if (mRes.confirm) {
              request.post(`/payment/mock/success/${id}`).then(() => {
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
          request.post(`/order/confirm/${this.data.orderId}`).then(() => {
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
          request.post(`/order/admin/accept/${id}`)
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
          request.post(`/order/admin/reject/${id}`, { reason: reason })
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
          request.post(`/order/admin/deliver/${id}`)
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
          request.post(`/order/admin/finish/${id}`)
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
          request.post(`/order/admin/cancel/${id}`, { reason: reason })
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
    }).then(() => {
      wx.hideLoading();
      wx.showToast({ title: '改价成功', icon: 'success' });
      this.closePriceModal();
      this.loadOrderDetail(this.data.orderId);
    }).catch(err => {
      wx.hideLoading();
      wx.showToast({ title: err.message || '改价失败', icon: 'none' });
    });
  }
});
