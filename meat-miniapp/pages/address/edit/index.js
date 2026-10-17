// pages/address/edit/index.js
const request = require('../../../utils/request');

Page({
  data: {
    formData: {
      id: null,
      contactName: '',
      contactPhone: '',
      province: '',
      city: '',
      district: '',
      address: '',
      fullAddress: '',
      longitude: null,
      latitude: null,
      isDefault: 0
    }
  },

  onLoad(options) {
    if (options.data) {
      try {
        const item = JSON.parse(decodeURIComponent(options.data));
        this.setData({ formData: item });
      } catch (e) {
        console.error(e);
      }
    }
  },

  chooseLocationOnMap() {
    wx.chooseLocation({
      success: (res) => {
        const { name, address, latitude, longitude } = res;
        const fullAddr = `${address || ''} ${name || ''}`.trim();

        this.setData({
          'formData.address': fullAddr,
          'formData.fullAddress': fullAddr,
          'formData.latitude': latitude,
          'formData.longitude': longitude
        });
        wx.showToast({ title: '已获取位置', icon: 'success' });
      },
      fail: (err) => {
        if (err.errMsg && err.errMsg.indexOf('auth') !== -1) {
          wx.showModal({
            title: '授权提示',
            content: '需要获取地理位置权限以在地图上选择精确配送地址',
            confirmText: '去设置',
            success: (mRes) => {
              if (mRes.confirm) wx.openSetting();
            }
          });
        }
      }
    });
  },

  onInputName(e) {
    this.setData({ 'formData.contactName': e.detail.value });
  },

  onInputPhone(e) {
    this.setData({ 'formData.contactPhone': e.detail.value });
  },

  onInputAddress(e) {
    this.setData({
      'formData.address': e.detail.value,
      'formData.fullAddress': e.detail.value
    });
  },

  onDefaultChange(e) {
    this.setData({ 'formData.isDefault': e.detail.value ? 1 : 0 });
  },

  onSave() {
    const f = this.data.formData;
    if (!f.contactName.trim()) {
      wx.showToast({ title: '请填写收货人姓名', icon: 'none' });
      return;
    }
    if (!f.contactPhone.trim()) {
      wx.showToast({ title: '请填写联系电话', icon: 'none' });
      return;
    }
    if (!f.address.trim()) {
      wx.showToast({ title: '请填写详细地址', icon: 'none' });
      return;
    }
    if (!f.longitude || !f.latitude) {
      wx.showToast({ title: '请点击上方在地图上选择定位', icon: 'none' });
      return;
    }

    wx.showLoading({ title: '保存中...' });
    request.post('/address/save', f).then(() => {
      wx.hideLoading();
      wx.showToast({ title: '保存成功', icon: 'success' });
      setTimeout(() => {
        wx.navigateBack();
      }, 1000);
    }).catch(() => {
      wx.hideLoading();
    });
  }
});
