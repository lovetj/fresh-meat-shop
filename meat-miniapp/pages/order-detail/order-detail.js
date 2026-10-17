// pages/order-detail/order-detail.js
const request = require('../../utils/request');
const { formatImageUrl } = require('../../utils/util');

Page({
  data: {
    orderId: null,
    order: {}
  },

  onLoad(options) {
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

  onCancelOrder() {
    wx.showModal({
      title: '取消确认',
      content: '确定要取消此订单吗？若已付款将自动原路退款。',
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
  }
});
