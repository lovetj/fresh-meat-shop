// pages/merchant/shop-setting/index.js
const request = require('../../../utils/request');
const auth = require('../../../utils/auth');

Page({
  data: {
    shopId: '',
    shop: {
      id: null,
      name: '',
      notice: '',
      phone: '',
      businessHours: '06:00 - 19:30',
      address: '',
      longitude: '',
      latitude: '',
      maxDeliveryDistance: 5.0,
      minOrderAmount: 20.0,
      deliveryFee: 3.0,
      status: 1
    },
    startTime: '06:00',
    endTime: '19:30',
    submitting: false
  },

  onLoad(options) {
    if (!auth.isAdmin()) {
      wx.showToast({ title: '无掌柜权限', icon: 'none' });
      setTimeout(() => {
        wx.switchTab({ url: '/pages/mine/mine' });
      }, 1000);
      return;
    }
    const shopId = (options && options.shopId) || wx.getStorageSync('adminShopId') || '';
    this.setData({ shopId });
    this.loadShopInfo(shopId);
  },

  loadShopInfo(shopId) {
    wx.showLoading({ title: '加载中...' });
    const params = shopId ? { shopId } : {};
    request.get('/shop/info', params)
      .then(res => {
        if (res) {
          const hours = this.parseBusinessHours(res.businessHours);
          this.setData({
            shop: res,
            shopId: res.id,
            startTime: hours.startTime,
            endTime: hours.endTime
          });
        }
      })
      .catch(err => {
        wx.showToast({ title: err.message || '加载店铺信息失败', icon: 'none' });
      })
      .finally(() => {
        wx.hideLoading();
      });
  },

  // 解析营业时间字符串，如 "06:00 - 19:30"
  parseBusinessHours(hoursStr) {
    let startTime = '06:00';
    let endTime = '19:30';
    if (hoursStr && typeof hoursStr === 'string') {
      const parts = hoursStr.split(/[-~至]/).map(s => s.trim());
      if (parts.length >= 2 && parts[0] && parts[1]) {
        startTime = parts[0];
        endTime = parts[1];
      }
    }
    return { startTime, endTime };
  },

  onStartTimeChange(e) {
    const startTime = e.detail.value;
    const businessHours = `${startTime} - ${this.data.endTime || '19:30'}`;
    this.setData({
      startTime,
      'shop.businessHours': businessHours
    });
  },

  onEndTimeChange(e) {
    const endTime = e.detail.value;
    const businessHours = `${this.data.startTime || '06:00'} - ${endTime}`;
    this.setData({
      endTime,
      'shop.businessHours': businessHours
    });
  },

  onStatusChange(e) {
    this.setData({
      'shop.status': e.detail.value ? 1 : 0
    });
  },

  // 地图选点获取门店坐标
  chooseLocationOnMap() {
    wx.chooseLocation({
      latitude: this.data.shop.latitude ? parseFloat(this.data.shop.latitude) : undefined,
      longitude: this.data.shop.longitude ? parseFloat(this.data.shop.longitude) : undefined,
      success: (res) => {
        this.setData({
          'shop.address': res.address || res.name,
          'shop.longitude': res.longitude,
          'shop.latitude': res.latitude
        });
        wx.showToast({ title: '已定位门店位置', icon: 'success' });
      },
      fail: (err) => {
        if (err.errMsg && err.errMsg.indexOf('auth deny') >= 0) {
          wx.showModal({
            title: '位置授权',
            content: '需要位置权限以打开地图选择门店位置，请开启权限',
            success: (modalRes) => {
              if (modalRes.confirm) {
                wx.openSetting();
              }
            }
          });
        }
      }
    });
  },

  // 提交保存店铺信息与配送设置
  onSubmitShopInfo(e) {
    if (this.data.submitting) return;

    const values = e.detail.value;
    const { status, id } = this.data.shop;

    if (!values.name || !values.name.trim()) {
      wx.showToast({ title: '请输入店铺名称', icon: 'none' });
      return;
    }
    if (!values.phone || !values.phone.trim()) {
      wx.showToast({ title: '请输入联系电话', icon: 'none' });
      return;
    }
    if (!values.address || !values.address.trim()) {
      wx.showToast({ title: '请输入门店详细地址', icon: 'none' });
      return;
    }
    if (!values.longitude || !values.latitude) {
      wx.showToast({ title: '请填写或从地图选择经纬度', icon: 'none' });
      return;
    }
    if (!values.maxDeliveryDistance || parseFloat(values.maxDeliveryDistance) <= 0) {
      wx.showToast({ title: '最大配送距离必须大于0', icon: 'none' });
      return;
    }

    const businessHours = (this.data.startTime && this.data.endTime)
      ? `${this.data.startTime} - ${this.data.endTime}`
      : (values.businessHours ? values.businessHours.trim() : (this.data.shop.businessHours || '06:00 - 19:30'));

    const payload = {
      id: id || this.data.shopId || null,
      name: values.name.trim(),
      phone: values.phone.trim(),
      notice: values.notice ? values.notice.trim() : '',
      businessHours: businessHours,
      address: values.address.trim(),
      longitude: parseFloat(values.longitude),
      latitude: parseFloat(values.latitude),
      maxDeliveryDistance: parseFloat(values.maxDeliveryDistance),
      minOrderAmount: values.minOrderAmount ? parseFloat(values.minOrderAmount) : 0,
      deliveryFee: values.deliveryFee ? parseFloat(values.deliveryFee) : 0,
      status: status !== undefined ? status : 1
    };

    this.setData({ submitting: true });
    wx.showLoading({ title: '保存中...' });

    request.put('/shop/admin/update', payload)
      .then(() => {
        wx.showToast({ title: '店铺设置已更新', icon: 'success' });
        setTimeout(() => {
          wx.navigateBack();
        }, 1200);
      })
      .catch(err => {
        wx.showToast({ title: err.message || '更新失败', icon: 'none' });
      })
      .finally(() => {
        this.setData({ submitting: false });
        wx.hideLoading();
      });
  }
});
