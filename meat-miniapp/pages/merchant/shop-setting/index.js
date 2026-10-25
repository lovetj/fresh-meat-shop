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
      status: 1
    },
    // 同城商家配送开关: 1-开通, 0-未开通
    merchantDeliveryEnabled: 1,
    // 外卖配送开关: 1-开通, 0-未开通（需求待定，暂作配置预留）
    takeoutEnabled: 0,
    // 同城多阶梯配送规则列表
    deliveryRules: [
      { minDistance: 0, maxDistance: 3.0, minOrderAmount: 20.0, deliveryFee: 3.0 }
    ],
    // 预约时效配置（分钟）
    pickupMinMinutes: 30,
    merchantMinMinutes: 45,
    takeoutMinMinutes: 45,
    timeSlotMinutes: 30,
    reserveDays: 1,
    slotOptions: [15, 30, 45, 60],
    slotOptionLabels: ['15 分钟', '30 分钟', '45 分钟', '60 分钟'],
    slotIndex: 1,
    reserveDayOptions: [
      { value: 0, label: '仅当天' },
      { value: 1, label: '当天 + 次日' },
      { value: 2, label: '当天 + 2 天' },
      { value: 3, label: '当天 + 3 天' }
    ],
    reserveDayIndex: 1,
    // 快递配送配置
    expressEnabled: 0,
    expressFee: '',
    expressRegions: [],
    // 配送方式 × 支付方式开关: 1-开启, 0-关闭
    // 到店自取
    pickupWxpayEnabled: 0,
    pickupCodEnabled: 1,
    // 同城商家配送
    merchantWxpayEnabled: 0,
    merchantCodEnabled: 1,
    // 外卖配送
    takeoutWxpayEnabled: 0,
    takeoutCodEnabled: 1,
    // 快递配送
    expressWxpayEnabled: 0,
    expressCodEnabled: 1,
    // 特殊地区配送状态下拉选项：0-可发 1-停发
    deliverOptions: ['可发', '停发'],
    startTime: '06:00',
    endTime: '19:30',
    // 营业状态三态：1-营业中(按每日营业时间自动判定), 2-休息中(手动，不受时间限制), 3-歇业中(禁止下单)
    statusOptions: [
      { value: 1, label: '营业中', color: 'open' },
      { value: 2, label: '休息中', color: 'rest' },
      { value: 3, label: '歇业中', color: 'closed' }
    ],
    statusHint: '',
    statusColor: 'open',
    previewStatusText: '营业中',
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
    if (!shopId) {
      wx.showToast({ title: '当前账号未关联门店', icon: 'none' });
      setTimeout(() => {
        wx.switchTab({ url: '/pages/mine/mine' });
      }, 1000);
      return;
    }
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
          let rules = [];
          if (Array.isArray(res.deliveryRules) && res.deliveryRules.length > 0) {
            rules = res.deliveryRules.map(r => ({
              id: r.id || null,
              minDistance: r.minDistance !== undefined ? r.minDistance : 0,
              maxDistance: (r.maxDistance === null || r.maxDistance === undefined) ? '' : r.maxDistance,
              minOrderAmount: r.minOrderAmount !== undefined ? r.minOrderAmount : 20.0,
              deliveryFee: r.deliveryFee !== undefined ? r.deliveryFee : 3.0
            }));
          } else {
            // 默认初始阶梯规则
            rules = [{
              minDistance: 0,
              maxDistance: 3.0,
              minOrderAmount: 20.0,
              deliveryFee: 3.0
            }];
          }

          // 快递配置：开关、基础运费与特殊地区规则
          const expressRegions = (Array.isArray(res.expressRegions) ? res.expressRegions : []).map(r => ({
            region: r.region || '',
            fee: r.fee === null || r.fee === undefined ? '' : r.fee,
            deliverable: r.deliverable === 0 ? 0 : 1
          }));

          const slotOptions = [15, 30, 45, 60];
          const timeSlotMinutes = this.normalizeMinutes(res.timeSlotMinutes, 30);
          let slotIndex = slotOptions.indexOf(timeSlotMinutes);
          if (slotIndex < 0) slotIndex = 1;
          const reserveDays = this.normalizeReserveDays(res.reserveDays);
          const reserveIndex = this.data.reserveDayOptions.findIndex(o => o.value === reserveDays);

          this.setData({
            shop: Object.assign({}, res, { status: this.normalizeStatus(res.status) }),
            shopId: res.id,
            merchantDeliveryEnabled: res.merchantDeliveryEnabled === 0 ? 0 : 1,
            takeoutEnabled: res.takeoutEnabled === 1 ? 1 : 0,
            deliveryRules: rules,
            pickupMinMinutes: this.normalizeMinutes(res.pickupMinMinutes, 30),
            merchantMinMinutes: this.normalizeMinutes(res.merchantMinMinutes, 45),
            takeoutMinMinutes: this.normalizeMinutes(res.takeoutMinMinutes, 45),
            timeSlotMinutes,
            slotIndex,
            reserveDays,
            reserveDayIndex: reserveIndex >= 0 ? reserveIndex : 0,
            expressEnabled: res.expressEnabled === 1 ? 1 : 0,
            expressFee: res.expressFee === null || res.expressFee === undefined ? '' : res.expressFee,
            expressRegions,
            // 支付方式开关：微信线上支付默认关闭，货到付款默认开启
            pickupWxpayEnabled: res.pickupWxpayEnabled === 1 ? 1 : 0,
            pickupCodEnabled: res.pickupCodEnabled === 0 ? 0 : 1,
            merchantWxpayEnabled: res.merchantWxpayEnabled === 1 ? 1 : 0,
            merchantCodEnabled: res.merchantCodEnabled === 0 ? 0 : 1,
            takeoutWxpayEnabled: res.takeoutWxpayEnabled === 1 ? 1 : 0,
            takeoutCodEnabled: res.takeoutCodEnabled === 0 ? 0 : 1,
            expressWxpayEnabled: res.expressWxpayEnabled === 1 ? 1 : 0,
            expressCodEnabled: res.expressCodEnabled === 0 ? 0 : 1,
            startTime: hours.startTime,
            endTime: hours.endTime
          }, () => this.refreshStatusView());
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
    }, () => this.refreshStatusView());
  },

  onEndTimeChange(e) {
    const endTime = e.detail.value;
    const businessHours = `${this.data.startTime || '06:00'} - ${endTime}`;
    this.setData({
      endTime,
      'shop.businessHours': businessHours
    }, () => this.refreshStatusView());
  },

  /** 历史两态数据兼容：0（原休息中）-> 2，非法值回落为营业中 */
  normalizeStatus(status) {
    const value = Number(status);
    if (value === 2 || value === 3) return value;
    if (value === 0) return 2;
    return 1;
  },

  /** 分钟数归一：非法或负数回落默认值 */
  normalizeMinutes(value, fallback) {
    const num = parseInt(value, 10);
    if (isNaN(num) || num < 0 || num > 1440) return fallback;
    return num;
  },

  /** 可预约天数归一：0 ~ 3，非法回落默认 1 天 */
  normalizeReserveDays(value) {
    const num = parseInt(value, 10);
    if (isNaN(num) || num < 0 || num > 3) return 1;
    return num;
  },

  /** 选择营业状态；歇业会关闭下单，需二次确认 */
  onStatusSelect(e) {
    const value = this.normalizeStatus(e.currentTarget.dataset.value);
    if (value === Number(this.data.shop.status)) return;

    if (value === 3) {
      wx.showModal({
        title: '确认歇业？',
        content: '歇业相当于关门，顾客将无法下单。确认后请点击底部「保存」生效。',
        confirmText: '确认歇业',
        confirmColor: '#FF6B6B',
        cancelText: '取消',
        success: (res) => {
          if (res.confirm) this.applyStatus(value);
        }
      });
      return;
    }
    this.applyStatus(value);
  },

  applyStatus(value) {
    this.setData({ 'shop.status': value }, () => this.refreshStatusView());
  },

  /** 刷新状态说明与「当前时间顾客端将展示的状态」预览 */
  refreshStatusView() {
    const status = this.normalizeStatus(this.data.shop.status);
    const hints = {
      1: '按「每日营业时间」自动切换：时段内展示营业中，超出时间自动展示休息中',
      2: '主动休息：不受营业时间限制，始终展示休息中；顾客可正常下单，订单待你恢复营业后处理',
      3: '歇业：相当于关门，顾客端将无法下单'
    };
    const colors = { 1: 'open', 2: 'rest', 3: 'closed' };
    let previewStatusText = '歇业中';
    if (status === 1) {
      previewStatusText = this.isInBusinessHours() ? '营业中' : '休息中';
    } else if (status === 2) {
      previewStatusText = '休息中';
    }

    this.setData({
      statusHint: hints[status],
      statusColor: colors[status],
      previewStatusText
    });
  },

  /** 当前时间是否在每日营业时段内（支持跨天，如 22:00 - 02:00） */
  isInBusinessHours() {
    const toMinutes = (text) => {
      const parts = String(text || '').split(':');
      if (parts.length < 2) return null;
      const hour = Number(parts[0]);
      const minute = Number(parts[1]);
      if (isNaN(hour) || isNaN(minute)) return null;
      return hour * 60 + minute;
    };

    const start = toMinutes(this.data.startTime);
    const end = toMinutes(this.data.endTime);
    if (start === null || end === null) return true;
    if (start === end) return true;

    const now = new Date();
    const nowMinutes = now.getHours() * 60 + now.getMinutes();
    if (start < end) {
      return nowMinutes >= start && nowMinutes < end;
    }
    return nowMinutes >= start || nowMinutes < end;
  },

  // ---------------- 多阶梯配送规则动态操作 ----------------
  addDeliveryRule() {
    const rules = [...this.data.deliveryRules];
    let nextMin = 0;
    let nextMax = 3.0;
    let nextMinAmt = 20.0;
    let nextFee = 3.0;

    if (rules.length > 0) {
      const last = rules[rules.length - 1];
      nextMin = Number(last.maxDistance) || 0;
      nextMax = +(nextMin + 3.0).toFixed(1);
      nextMinAmt = +(Number(last.minOrderAmount || 0) + 10).toFixed(1);
      nextFee = +(Number(last.deliveryFee || 0) + 2).toFixed(1);
    }

    rules.push({
      minDistance: nextMin,
      maxDistance: nextMax,
      minOrderAmount: nextMinAmt,
      deliveryFee: nextFee
    });

    this.setData({ deliveryRules: rules });
  },

  deleteDeliveryRule(e) {
    const index = Number(e.currentTarget.dataset.index);
    const rules = [...this.data.deliveryRules];
    if (rules.length <= 1) {
      wx.showToast({ title: '至少保留一条配送规则', icon: 'none' });
      return;
    }

    const target = rules[index];
    if (!target) return;

    const rangeText = `${target.minDistance}km - ${target.maxDistance}km`;

    wx.showModal({
      title: '删除配送阶梯',
      content: `确定删除「阶梯 ${index + 1}（${rangeText}）」吗？删除后下一阶梯的起始距离会自动衔接。`,
      confirmText: '删除',
      confirmColor: '#FF6B6B',
      cancelText: '取消',
      success: (res) => {
        if (!res.confirm) return;

        const nextRules = [...this.data.deliveryRules];
        const removed = nextRules[index];
        nextRules.splice(index, 1);

        // 删除后自动衔接：下一阶梯的起始距离继承被删阶梯的起始距离，避免阶梯断档
        const next = nextRules[index];
        if (removed && next) {
          nextRules[index] = { ...next, minDistance: removed.minDistance };
        }

        this.setData({ deliveryRules: nextRules });
        wx.showToast({ title: '已删除该阶梯', icon: 'none' });
      }
    });
  },

  onRuleFieldInput(e) {
    const { index, field } = e.currentTarget.dataset;
    const value = e.detail.value;
    const idx = Number(index);
    const rules = [...this.data.deliveryRules];
    if (!rules[idx]) return;

    rules[idx] = { ...rules[idx], [field]: value };

    // 修改截止距离时自动同步下一阶梯的起始距离，保持阶梯首尾衔接
    if (field === 'maxDistance' && rules[idx + 1]) {
      rules[idx + 1] = { ...rules[idx + 1], minDistance: value };
    }

    this.setData({ deliveryRules: rules });
  },

  // ---------------- 预约时效配置 ----------------
  onMinMinutesInput(e) {
    const field = e.currentTarget.dataset.field;
    if (!field) return;
    this.setData({ [field]: e.detail.value });
  },

  onTimeSlotChange(e) {
    const index = Number(e.detail.value) || 0;
    this.setData({
      slotIndex: index,
      timeSlotMinutes: this.data.slotOptions[index] || 30
    });
  },

  onReserveDaysChange(e) {
    const index = Number(e.detail.value) || 0;
    const option = this.data.reserveDayOptions[index];
    this.setData({
      reserveDayIndex: index,
      reserveDays: option ? option.value : 0
    });
  },

  // ---------------- 外卖配送开关 ----------------
  onTakeoutEnabledChange(e) {
    this.setData({ takeoutEnabled: e.detail.value ? 1 : 0 });
  },

  // ---------------- 同城商家配送开关 ----------------
  onMerchantDeliveryChange(e) {
    const enabled = e.detail.value ? 1 : 0;
    if (enabled === 0) {
      wx.showToast({ title: '已关闭商家配送，顾客仅能选自取或快递', icon: 'none' });
    }
    this.setData({ merchantDeliveryEnabled: enabled });
  },

  // ---------------- 快递配送配置 ----------------
  onExpressEnabledChange(e) {
    this.setData({ expressEnabled: e.detail.value ? 1 : 0 });
  },

  // ---------------- 配送方式 × 支付方式开关 ----------------
  onPaySwitchChange(e) {
    const key = e.currentTarget.dataset.key;
    if (!key) return;
    this.setData({ [key]: e.detail.value ? 1 : 0 });
  },

  onExpressFeeInput(e) {
    this.setData({ expressFee: e.detail.value });
  },

  addExpressRegion() {
    const regions = [...this.data.expressRegions];
    regions.push({ region: '', fee: '', deliverable: 1 });
    this.setData({ expressRegions: regions });
  },

  deleteExpressRegion(e) {
    const index = Number(e.currentTarget.dataset.index);
    const target = this.data.expressRegions[index];
    if (!target) return;

    const regionName = (target.region || '').trim() || `第 ${index + 1} 行地区`;

    wx.showModal({
      title: '删除特殊地区',
      content: `确定删除「${regionName}」的快递规则吗？删除后该地区将按基础运费计费。`,
      confirmText: '删除',
      confirmColor: '#FF6B6B',
      cancelText: '取消',
      success: (res) => {
        if (!res.confirm) return;
        const regions = this.data.expressRegions.filter((_, i) => i !== index);
        this.setData({ expressRegions: regions });
        wx.showToast({ title: '已删除该地区', icon: 'none' });
      }
    });
  },

  /** 特殊地区：地图选择省级/直辖市，仅取第一级省份，忽略市与区县 */
  onExpressRegionPick(e) {
    const idx = Number(e.currentTarget.dataset.index);
    const value = e.detail.value || [];
    const province = (value[0] || '').trim();
    if (!province) return;
    const regions = [...this.data.expressRegions];
    if (!regions[idx]) return;
    regions[idx] = { ...regions[idx], region: province };
    this.setData({ expressRegions: regions });
  },

  onExpressRegionInput(e) {
    const { index, field } = e.currentTarget.dataset;
    const idx = Number(index);
    const regions = [...this.data.expressRegions];
    if (!regions[idx]) return;
    regions[idx] = { ...regions[idx], [field]: e.detail.value };
    this.setData({ expressRegions: regions });
  },

  // 特殊地区「可发/停发」下拉选择
  onExpressRegionDeliverableChange(e) {
    const idx = Number(e.currentTarget.dataset.index);
    const selected = Number(e.detail.value);
    const regions = [...this.data.expressRegions];
    if (!regions[idx]) return;
    regions[idx] = { ...regions[idx], deliverable: selected === 1 ? 0 : 1 };
    this.setData({ expressRegions: regions });
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

  /**
   * 校验并清洗同城配送阶梯规则
   * 阶梯必须首尾连贯：第 1 段从 0km 起，之后每段的起始距离等于上一段截止距离
   * @returns {Array|null} 校验失败返回 null
   */
  buildCleanRules(rawRules) {
    const cleanRules = [];
    const EPS = 0.01;
    let prevMaxDistance = null;

    for (let i = 0; i < rawRules.length; i++) {
      const r = rawRules[i];
      const minD = parseFloat(r.minDistance);
      const minAmt = parseFloat(r.minOrderAmount) || 0;
      const fee = parseFloat(r.deliveryFee) || 0;

      if (isNaN(minD) || minD < 0) {
        wx.showToast({ title: `第${i + 1}个阶梯的起始距离必须≥0`, icon: 'none' });
        return null;
      }

      // 连贯性校验：起点必须承接上一段终点
      if (i === 0) {
        if (Math.abs(minD) > EPS) {
          wx.showToast({ title: '第1个阶梯的起始距离必须为 0km', icon: 'none' });
          return null;
        }
      } else if (prevMaxDistance !== null && Math.abs(minD - prevMaxDistance) > EPS) {
        wx.showToast({
          title: `第${i + 1}个阶梯的起始距离必须为 ${prevMaxDistance}km，与上一阶梯衔接`,
          icon: 'none'
        });
        return null;
      }

      const maxD = parseFloat(r.maxDistance);
      if (isNaN(maxD) || maxD <= minD) {
        wx.showToast({ title: `第${i + 1}个阶梯的截止距离必须大于起始距离`, icon: 'none' });
        return null;
      }

      // 记录本段终点，供下一段衔接校验
      prevMaxDistance = maxD;

      cleanRules.push({
        id: r.id || null,
        minDistance: minD,
        maxDistance: maxD,
        minOrderAmount: minAmt,
        deliveryFee: fee,
        sortOrder: i + 1
      });
    }

    return cleanRules;
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

    // 校验多阶梯配送规则（仅同城商家配送开启时校验）
    const merchantDeliveryEnabled = this.data.merchantDeliveryEnabled === 1 ? 1 : 0;
    let cleanRules = [];

    if (merchantDeliveryEnabled === 1) {
      const rawRules = this.data.deliveryRules || [];
      if (rawRules.length === 0) {
        wx.showToast({ title: '请至少配置一条配送阶梯规则', icon: 'none' });
        return;
      }
      cleanRules = this.buildCleanRules(rawRules);
      if (!cleanRules) return;
    }

    const businessHours = (this.data.startTime && this.data.endTime)
      ? `${this.data.startTime} - ${this.data.endTime}`
      : (values.businessHours ? values.businessHours.trim() : (this.data.shop.businessHours || '06:00 - 19:30'));

    // 校验快递配送配置
    const takeoutEnabled = this.data.takeoutEnabled === 1 ? 1 : 0;
    const expressEnabled = this.data.expressEnabled === 1 ? 1 : 0;
    const expressFeeNum = parseFloat(this.data.expressFee);
    if (expressEnabled === 1 && this.data.expressFee !== ''
      && (isNaN(expressFeeNum) || expressFeeNum < 0)) {
      wx.showToast({ title: '快递基础运费需为不小于 0 的数字', icon: 'none' });
      return;
    }

    const cleanRegions = [];
    for (const r of this.data.expressRegions) {
      const regionName = (r.region || '').trim();
      if (!regionName) {
        wx.showToast({ title: '请填写特殊地区名称，或删除该行', icon: 'none' });
        return;
      }
      const feeNum = parseFloat(r.fee);
      if (r.fee !== '' && (isNaN(feeNum) || feeNum < 0)) {
        wx.showToast({ title: `「${regionName}」的运费需为不小于 0 的数字`, icon: 'none' });
        return;
      }
      cleanRegions.push({
        region: regionName,
        fee: (r.fee === '' || isNaN(feeNum)) ? null : feeNum,
        deliverable: r.deliverable === 0 ? 0 : 1
      });
    }

    // 校验支付方式开关：已开通的配送方式至少保留一种可用支付方式
    const payChecks = [
      { label: '到店自取', active: true, wxKey: 'pickupWxpayEnabled', codKey: 'pickupCodEnabled' },
      { label: '同城商家配送', active: merchantDeliveryEnabled === 1, wxKey: 'merchantWxpayEnabled', codKey: 'merchantCodEnabled' },
      { label: '外卖配送', active: takeoutEnabled === 1, wxKey: 'takeoutWxpayEnabled', codKey: 'takeoutCodEnabled' },
      { label: '快递配送', active: expressEnabled === 1, wxKey: 'expressWxpayEnabled', codKey: 'expressCodEnabled' }
    ];
    for (const check of payChecks) {
      if (!check.active) continue;
      if (this.data[check.wxKey] !== 1 && this.data[check.codKey] !== 1) {
        wx.showToast({ title: `「${check.label}」至少开启一种支付方式`, icon: 'none' });
        return;
      }
    }

    // 校验预约时效配置（分钟数需为 0 ~ 1440 的整数）
    const minuteFields = [
      { key: 'pickupMinMinutes', label: '到店自取最小备货时间' },
      { key: 'merchantMinMinutes', label: '商家配送最小送达时间' },
      { key: 'takeoutMinMinutes', label: '外卖配送最小送达时间' }
    ];
    const appointment = {};
    for (const field of minuteFields) {
      const raw = this.data[field.key];
      const num = parseInt(raw, 10);
      if (isNaN(num) || num < 0 || num > 1440) {
        wx.showToast({ title: `${field.label}需为 0~1440 的整数分钟`, icon: 'none' });
        return;
      }
      appointment[field.key] = num;
    }
    appointment.timeSlotMinutes = Number(this.data.timeSlotMinutes) || 30;
    appointment.reserveDays = Number(this.data.reserveDays) || 0;

    const payload = {
      id: id || this.data.shopId || null,
      name: values.name.trim(),
      phone: values.phone.trim(),
      notice: values.notice ? values.notice.trim() : '',
      businessHours: businessHours,
      address: values.address.trim(),
      longitude: parseFloat(values.longitude),
      latitude: parseFloat(values.latitude),
      merchantDeliveryEnabled,
      takeoutEnabled,
      deliveryRules: cleanRules,
      expressEnabled,
      expressFee: isNaN(expressFeeNum) ? 0 : expressFeeNum,
      expressRegions: cleanRegions,
      // 配送方式 × 支付方式开关
      pickupWxpayEnabled: this.data.pickupWxpayEnabled === 1 ? 1 : 0,
      pickupCodEnabled: this.data.pickupCodEnabled === 1 ? 1 : 0,
      merchantWxpayEnabled: this.data.merchantWxpayEnabled === 1 ? 1 : 0,
      merchantCodEnabled: this.data.merchantCodEnabled === 1 ? 1 : 0,
      takeoutWxpayEnabled: this.data.takeoutWxpayEnabled === 1 ? 1 : 0,
      takeoutCodEnabled: this.data.takeoutCodEnabled === 1 ? 1 : 0,
      expressWxpayEnabled: this.data.expressWxpayEnabled === 1 ? 1 : 0,
      expressCodEnabled: this.data.expressCodEnabled === 1 ? 1 : 0,
      status: status !== undefined ? status : 1
    };

    Object.assign(payload, appointment);

    this.setData({ submitting: true });
    wx.showLoading({ title: '保存中...' });

    request.put('/shop/admin/update', payload)
      .then(() => {
        wx.showToast({ title: '店铺及配送规则已更新', icon: 'success' });
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
