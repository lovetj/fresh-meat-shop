// pages/checkout/checkout.js
const request = require('../../utils/request');
const auth = require('../../utils/auth');
const cart = require('../../utils/cart');
const { normalizeShopStatus, isOrderBlocked } = require('../../utils/shop-status');
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
    // 门店是否歇业（歇业中禁止下单，底部按钮置灰）
    shopClosed: false,

    // 配送方式: 1-自取, 2-商家配送, 3-外卖(暂不支持), 4-快递配送
    deliveryType: 1,

    // 自提信息
    pickupName: '',
    pickupPhone: '',

    // 期望送达 / 期望到店自取时间（按门店预约时效配置动态生成）
    timeOptionDays: [],
    timeOptionTip: '',
    activeTimeDayIndex: 0,
    selectedTimeValue: '',
    selectedTimeLabel: '',
    // 时间档位默认最多展示 3 行，超出时点击「展示全部」展开
    timeExpanded: false,
    timeSlotOverflow: false,
    currentSlotCount: 0,

    // 商家配送收货地址及距离校验
    selectedAddress: null,
    // 地址列表页选点后回写的临时地址
    tempSelectedAddress: null,
    distanceCheckResult: null,
    // 快递配送运费试算结果（含特殊地区/停发判断）
    expressCheckResult: null,
    isSubmitDisabled: false,
    submitButtonText: '提交订单',

    // 动态配送费与起送价联动
    currentMinOrderAmount: '0.00',
    shortAmount: 0,
    showRulesModal: false,
    // 超范围 / 切换配送方式提示弹层
    showRangePopup: false,
    rangePopup: { title: '', subtitle: '', options: [] },

    // 支付方式: 1-微信线上支付, 2-货到付款；具体可选哪些由「当前配送方式」在掌柜端的配置决定
    payType: 2,
    // 当前配送方式下店铺已开启的支付方式
    payWxpayEnabled: false,
    payCodEnabled: true,

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
    // 页面停留较久后返回时刷新时间档位，避免展示已过期或已不满足时效的档位
    if (this._timeLoadedAt && Date.now() - this._timeLoadedAt > 60000 && this.data.deliveryType !== 4) {
      this.loadTimeOptions();
    }

    // 监听从地址列表选回的新地址（地址列表直接写入本页 data.tempSelectedAddress）
    const address = this.data.tempSelectedAddress;
    if (address) {
      this.setData({ selectedAddress: address, tempSelectedAddress: null }, () => {
        this.reevaluateAddress(address);
      });
      return;
    }

    // 页面停留期间切换了身份（顾客 ↔ 掌柜）时，旧地址属于另一个账号，
    // 提交时会被后端判定为「收货地址不存在」，因此按新身份重新加载默认地址
    const token = auth.getActiveToken();
    if (this._addressToken !== undefined && this._addressToken !== token) {
      this.setData({ selectedAddress: null, distanceCheckResult: null, expressCheckResult: null }, () => {
        this.loadDefaultAddress();
        this.calcTotal();
      });
    }
  },

  /**
   * 收货地址变更后重新判断配送能力（任何配送方式下都会重算）
   * - 商家配送：重新测距，超范围时引导到店自取/快递配送/更换地址
   * - 到店自取、快递配送：同样测距，命中同城范围时提示可切换配送方式
   */
  reevaluateAddress(address) {
    if (!address) return;

    const { deliveryType } = this.data;

    if (deliveryType === 4) {
      // 快递配送：先按新地址重算运费与停发判断
      this.checkExpressFee(address);
    } else if (deliveryType === 1) {
      this.calcTotal();
    }

    if (address.longitude && address.latitude) {
      // 距离判断与配送方式无关，始终重新测距；仅在自取/快递时提示可切换
      this.checkAddressDistance(address, { suggestSwitch: deliveryType !== 2 });
    } else if (deliveryType === 2) {
      this.checkAddressDistance(address);
    }
  },

  loadShopInfo(shopId) {
    const params = {};
    if (shopId) {
      params.shopId = shopId;
    }
    request.get('/shop/info', params, { silent: true }).then((rawRes) => {
      if (rawRes) {
        // 归一化营业状态：displayStatus 1-营业中 / 2-休息中 / 3-歇业中
        const res = normalizeShopStatus(rawRes);
        const rules = Array.isArray(res.deliveryRules) ? res.deliveryRules : [];
        // 仅统计有截止距离的有效阶梯（历史遗留的无截止距离规则不参与计算）
        const validRules = rules.filter(r => r.maxDistance !== null && r.maxDistance !== undefined && Number(r.maxDistance) > 0);
        const firstRule = validRules[0] || null;
        let maxDist = 0;
        validRules.forEach(r => {
          const max = Number(r.maxDistance) || 0;
          if (max > maxDist) {
            maxDist = max;
          }
        });
        const merchantEnabled = res.merchantDeliveryEnabled !== undefined && res.merchantDeliveryEnabled !== null
          ? Boolean(res.merchantDeliveryEnabled)
          : true;
        const expressEnabled = res.expressEnabled === 1;
        const deliveryRangeText = !merchantEnabled
          ? '未开通同城配送'
          : (validRules.length === 0 ? '同城配送' : `限 ${maxDist || 5}km`);

        // 歇业中：进入结算页即锁定，不允许提交
        const shopClosed = isOrderBlocked(res);

        const nextPatch = {
          shopInfo: {
            ...res,
            merchantDeliveryEnabled: merchantEnabled,
            expressEnabled: expressEnabled ? 1 : 0,
            deliveryRangeText: deliveryRangeText
          },
          shopId: res.id,
          shopClosed,
          deliveryFee: Number(firstRule ? firstRule.deliveryFee : 0).toFixed(2),
          currentMinOrderAmount: Number(firstRule ? firstRule.minOrderAmount : 0).toFixed(2)
        };

        // 门店配置变化时兜底当前已选方式
        if (this.data.deliveryType === 2 && !merchantEnabled) {
          nextPatch.deliveryType = 1;
          nextPatch.distanceCheckResult = null;
        } else if (this.data.deliveryType === 4 && !expressEnabled) {
          nextPatch.deliveryType = 1;
        }

        // 支付方式随「当前配送方式 + 店铺配置」联动，已选方式失效时自动兜底
        const finalDeliveryType = nextPatch.deliveryType || this.data.deliveryType;
        Object.assign(nextPatch, this.resolvePayPatch(finalDeliveryType, nextPatch.shopInfo));

        this.setData(nextPatch, () => {
          this.calcTotal();
          this.loadTimeOptions();
          if (this.data.deliveryType === 4 && this.data.selectedAddress) {
            this.checkExpressFee(this.data.selectedAddress);
          }
        });

        // 首次进入即歇业：只提示一次，避免反复打断
        if (shopClosed && !this._closedTipShown) {
          this._closedTipShown = true;
          wx.showModal({
            title: '门店已歇业',
            content: res.statusTip || '该门店当前处于歇业状态，暂不接单。可返回切换其他门店或稍后再来～',
            showCancel: false,
            confirmText: '我知道了'
          });
        }
      }
    });
  },

  /**
   * 取某配送方式下店铺已开启的支付方式（掌柜端「配送方式 × 支付方式」配置）
   * 字段缺失时与后端默认值保持一致：微信线上支付未开通、货到付款可用
   */
  getPayCapability(deliveryType, shopInfo) {
    const shop = shopInfo || this.data.shopInfo || {};
    const confMap = {
      1: { wxpay: shop.pickupWxpayEnabled, cod: shop.pickupCodEnabled },
      2: { wxpay: shop.merchantWxpayEnabled, cod: shop.merchantCodEnabled },
      3: { wxpay: shop.takeoutWxpayEnabled, cod: shop.takeoutCodEnabled },
      4: { wxpay: shop.expressWxpayEnabled, cod: shop.expressCodEnabled }
    };
    const conf = confMap[deliveryType] || confMap[4];
    return {
      wxpay: conf.wxpay === 1,
      cod: conf.cod === undefined || conf.cod === null ? true : conf.cod === 1
    };
  },

  /** 刷新支付方式可用性，并在当前已选方式不可用时自动切换（都不可用时清空选择） */
  resolvePayPatch(deliveryType, shopInfo) {
    const capability = this.getPayCapability(deliveryType, shopInfo);
    let payType = this.data.payType;
    if (payType === 1 && !capability.wxpay) {
      payType = capability.cod ? 2 : null;
    } else if (payType === 2 && !capability.cod) {
      payType = capability.wxpay ? 1 : null;
    }
    return {
      payWxpayEnabled: capability.wxpay,
      payCodEnabled: capability.cod,
      payType
    };
  },

  /** 快递配送运费试算：按地址匹配门店特殊地区规则 */
  checkExpressFee(address) {
    if (!address || !address.id) {
      this.setData({ expressCheckResult: null }, () => this.calcTotal());
      return;
    }
    wx.showLoading({ title: '计算快递运费...' });
    // 必须携带「当前生效身份」的 Token：地址是按该身份保存的，后端会校验地址归属
    request.post('/shop/express-check', {
      shopId: this.data.shopId,
      addressId: address.id
    }, Object.assign({ silent: true }, this.authHeader())).then((res) => {
      this.setData({ expressCheckResult: res }, () => this.calcTotal());
    }).catch((err) => {
      this.setData({
        expressCheckResult: {
          canDeliver: false,
          fee: 0,
          message: (err && err.message) || '该地址暂不支持快递配送'
        }
      }, () => this.calcTotal());
    }).finally(() => {
      wx.hideLoading();
    });
  },

  /**
   * 加载「期望送达 / 期望到店自取」时间可选项
   * 档位由后端按「门店最小备货/配送时长 + 档位间隔 + 可预约天数 + 每日营业时间」实时计算
   */
  loadTimeOptions() {
    const { deliveryType, shopId } = this.data;
    // 快递配送按天计，不需要选择送达时间
    if (deliveryType === 4 || !shopId) {
      this.setData({
        timeOptionDays: [],
        timeOptionTip: '',
        activeTimeDayIndex: 0,
        selectedTimeValue: '',
        selectedTimeLabel: ''
      });
      return;
    }

    this._timeLoadedAt = Date.now();
    request.get('/shop/delivery-time-options', { shopId, deliveryType }, { silent: true }).then((res) => {
      const days = res && Array.isArray(res.days) ? res.days : [];
      const prev = this.data.selectedTimeValue;
      let activeIndex = 0;
      let matched = null;

      // 已选时间仍有效则保留，避免门店配置刷新后丢失顾客选择
      days.forEach((day, di) => {
        (day.slots || []).forEach((slot) => {
          if (slot.value === prev) {
            activeIndex = di;
            matched = { value: slot.value, label: `${day.dayLabel} ${slot.label}` };
          }
        });
      });

      // 默认选中最早可预约档位
      if (!matched && days.length > 0) {
        const firstDay = days[0];
        const firstSlot = (firstDay.slots || [])[0];
        if (firstSlot) {
          matched = { value: firstSlot.value, label: `${firstDay.dayLabel} ${firstSlot.label}` };
        }
      }

      const activeDay = days[activeIndex] || null;
      this.setData({
        timeOptionDays: days,
        timeOptionTip: (res && res.tip) || '',
        activeTimeDayIndex: activeIndex,
        selectedTimeValue: matched ? matched.value : '',
        selectedTimeLabel: matched ? matched.label : '',
        currentSlotCount: activeDay && activeDay.slots ? activeDay.slots.length : 0,
        timeExpanded: false,
        timeSlotOverflow: false
      }, () => {
        this.calcTotal();
        this.checkTimeSlotOverflow();
      });
    }).catch(() => {
      this.setData({
        timeOptionDays: [],
        timeOptionTip: '时间档位加载失败，掌柜将按最快时效为您安排',
        activeTimeDayIndex: 0,
        selectedTimeValue: '',
        selectedTimeLabel: '',
        currentSlotCount: 0,
        timeExpanded: false,
        timeSlotOverflow: false
      }, () => this.calcTotal());
    });
  },

  /**
   * 检测当前日期档位是否超过 3 行，超出时才显示「展示全部」
   * 折叠状态下父容器虽被裁剪，但子节点 boundingClientRect 仍返回完整高度，可直接判断
   */
  checkTimeSlotOverflow() {
    const count = this.data.currentSlotCount;
    const apply = (overflow) => {
      if (overflow === this.data.timeSlotOverflow) return;
      this.setData({
        timeSlotOverflow: overflow,
        timeExpanded: overflow ? this.data.timeExpanded : false
      });
    };

    const query = wx.createSelectorQuery();
    query.select('#timeSlotGrid').boundingClientRect();
    query.exec((res) => {
      const rect = res && res[0];
      if (!rect) {
        // 取不到布局信息时按约 4 列/行估算兜底
        apply(count > 12);
        return;
      }
      let windowWidth = 375;
      try {
        const info = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
        windowWidth = (info && info.windowWidth) || 375;
      } catch (e) {
        windowWidth = 375;
      }
      // 3 行 = 3 × 66rpx + 2 × 16rpx 间距
      const maxHeight = (3 * 66 + 2 * 16) * windowWidth / 750;
      apply(rect.height > maxHeight + 1);
    });
  },

  /** 展开 / 收起时间档位 */
  onToggleTimeExpand() {
    this.setData({ timeExpanded: !this.data.timeExpanded });
  },

  /** 切换日期分组：默认选中该天最早档位，并回到三行折叠态 */
  onSelectTimeDay(e) {
    const index = Number(e.currentTarget.dataset.index);
    const day = this.data.timeOptionDays[index];
    if (!day) return;
    const firstSlot = (day.slots || [])[0];
    this.setData({
      activeTimeDayIndex: index,
      selectedTimeValue: firstSlot ? firstSlot.value : '',
      selectedTimeLabel: firstSlot ? `${day.dayLabel} ${firstSlot.label}` : '',
      currentSlotCount: (day.slots || []).length,
      timeExpanded: false,
      timeSlotOverflow: false
    }, () => {
      this.calcTotal();
      this.checkTimeSlotOverflow();
    });
  },

  /** 选择具体时间档位 */
  onSelectTimeSlot(e) {
    const { value, label } = e.currentTarget.dataset;
    const day = this.data.timeOptionDays[this.data.activeTimeDayIndex];
    this.setData({
      selectedTimeValue: value,
      selectedTimeLabel: day ? `${day.dayLabel} ${label}` : label
    }, () => this.calcTotal());
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

  /**
   * 按「当前生效身份」取 Token：
   * 收货地址、下单、支付必须与当前身份一致，否则会出现地址归属校验失败或订单串号
   */
  authHeader() {
    const token = auth.getActiveToken();
    return token ? { header: { Authorization: `Bearer ${token}` } } : {};
  },

  loadDefaultAddress() {
    if (!auth.isLoggedIn()) return;
    // 记录本次加载地址所使用的身份 Token，供 onShow 判断身份是否已切换
    this._addressToken = auth.getActiveToken();
    request.get('/address/default', {}, Object.assign({ silent: true }, this.authHeader())).then((res) => {
      if (res) {
        this.setData({ selectedAddress: res });
        if (this.data.deliveryType === 2) {
          this.checkAddressDistance(res);
        } else if (this.data.deliveryType === 4) {
          this.checkExpressFee(res);
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
    } else if (deliveryType === 4) {
      // 快递配送：运费由后端按门店快递配置与特殊地区规则试算
      const expressResult = this.data.expressCheckResult;
      if (expressResult) {
        fee = Number(expressResult.fee || 0);
        canDeliver = Boolean(expressResult.canDeliver);
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
    } else if (deliveryType === 4) {
      // 快递配送：只需收货地址，不受同城距离与起送价限制，但受特殊地区停发限制
      if (!this.data.selectedAddress) {
        isSubmitDisabled = true;
        submitButtonText = '请选择收货地址';
      } else if (!canDeliver) {
        isSubmitDisabled = true;
        submitButtonText = '该地址暂不支持快递';
      }
    }

    // 到店自取 / 商家配送：有可预约档位时必须选择期望时间
    if (!isSubmitDisabled && !this.data.selectedTimeValue && this.data.timeOptionDays.length > 0) {
      isSubmitDisabled = true;
      submitButtonText = deliveryType === 1 ? '请选择自取时间' : '请选择送达时间';
    }

    // 支付方式：当前配送方式下无可用支付方式时直接禁止提交
    if (!this.data.payWxpayEnabled && !this.data.payCodEnabled) {
      isSubmitDisabled = true;
      submitButtonText = '暂无可用的支付方式';
    } else if (!payType) {
      isSubmitDisabled = true;
      submitButtonText = '请选择支付方式';
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
    const { shopInfo, selectedAddress } = this.data;

    // 门店配置校验：未开通同城配送 / 快递配送时给出明确提示
    if (type === 2 && shopInfo.merchantDeliveryEnabled === false) {
      const options = [{
        key: 'pickup',
        icon: '🏪',
        label: '到店自取',
        desc: '免配送费，到店提货',
        primary: true
      }];
      if (shopInfo.expressEnabled === 1) {
        options.push({ key: 'express', icon: '📦', label: '快递配送', desc: '快递到家，商家发货后回填单号' });
      }
      options.push({ key: 'keep', icon: '✓', label: '留在当前方式', desc: '继续使用当前选择的配送方式' });

      this.openRangePopup({
        title: '暂未开通同城配送',
        subtitle: '本店暂未开通同城商家配送，可选择其他配送方式下单',
        options
      });
      return;
    }
    if (type === 4 && shopInfo.expressEnabled !== 1) {
      wx.showToast({ title: '本店暂未开通快递配送', icon: 'none' });
      return;
    }

    this.setData(Object.assign({
      deliveryType: type,
      // 切换到自取/快递时清空同城测距结果，避免残留的距离与运费影响结算
      distanceCheckResult: type === 2 ? this.data.distanceCheckResult : null
    }, this.resolvePayPatch(type, shopInfo)), () => {
      this.calcTotal();
      this.loadTimeOptions();
      if (type === 2 && selectedAddress) {
        this.checkAddressDistance(selectedAddress);
      } else if (type === 4 && selectedAddress) {
        this.checkExpressFee(selectedAddress);
        // 快递配送同样复用距离判断：地址在同城范围内时提示是否改为商家配送
        if (selectedAddress.longitude && selectedAddress.latitude) {
          this.checkAddressDistance(selectedAddress, { suggestSwitch: true });
        }
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

  checkAddressDistance(address, options = {}) {
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
        // 仅当前为商家配送时需要引导更换配送方式
        if (this.data.deliveryType === 2) {
          this.showRangeActions(res);
        }
      } else if (options.suggestSwitch && this.data.deliveryType !== 2) {
        // 新地址在商家配送范围内，提示是否切换配送方式
        this.promptSwitchDelivery(res);
      }
    }).catch(() => {
      wx.hideLoading();
    });
  },

  /**
   * 新地址在同城配送范围内：提示是否切换为商家配送（可保留当前方式）
   */
  promptSwitchDelivery(result) {
    const { deliveryType, shopInfo } = this.data;
    if (!result || deliveryType === 2) return;
    if (shopInfo.merchantDeliveryEnabled === false) return;

    const distance = (Number(result.distance) || 0).toFixed(2);
    const fee = Number(result.deliveryFee !== undefined ? result.deliveryFee : 0).toFixed(2);
    const minAmount = Number(result.minOrderAmount !== undefined ? result.minOrderAmount : 0).toFixed(2);

    const options = [{
      key: 'merchant',
      icon: '🛵',
      label: '商家配送',
      desc: `配送费 ¥${fee} · 起送 ¥${minAmount} · 约 ${distance}km`,
      primary: true
    }];
    if (deliveryType !== 1) {
      options.push({ key: 'pickup', icon: '🏪', label: '到店自取', desc: '免配送费，到店提货' });
    }
    if (shopInfo.expressEnabled === 1 && deliveryType !== 4) {
      options.push({ key: 'express', icon: '📦', label: '快递配送', desc: '快递到家，商家发货后回填单号' });
    }
    options.push({ key: 'keep', icon: '✓', label: '保持当前方式', desc: '继续使用当前选择的配送方式' });

    this.openRangePopup({
      title: '该地址支持同城配送',
      subtitle: `距门店约 ${distance}km，在商家配送范围内`,
      options
    });
  },

  // 超出配送范围：底部弹层给出「到店自取 / 快递配送 / 更换地址」
  showRangeActions(result) {
    const { shopInfo, distanceCheckResult } = this.data;
    const info = result || distanceCheckResult || null;
    const expressEnabled = shopInfo.expressEnabled === 1;

    const options = [{
      key: 'pickup',
      icon: '🏪',
      label: '到店自取',
      desc: '免配送费，到店提货',
      primary: !expressEnabled
    }];
    if (expressEnabled) {
      options.push({
        key: 'express',
        icon: '📦',
        label: '快递配送',
        desc: '快递到家，商家发货后回填单号',
        primary: true
      });
    }
    options.push({ key: 'address', icon: '📍', label: '更换收货地址', desc: '选择店铺配送范围内的地址' });

    const distance = info && info.distance !== undefined ? Number(info.distance).toFixed(2) : '';
    this.openRangePopup({
      title: '超出配送范围',
      subtitle: distance
        ? `当前地址距门店约 ${distance}km，超出本店最大同城配送范围`
        : '当前收货地址超出本店最大同城配送范围',
      options
    });
  },

  // 配送提示弹层：打开 / 关闭 / 选项点击
  openRangePopup(popup) {
    this.setData({
      showRangePopup: true,
      rangePopup: popup
    });
  },

  closeRangePopup() {
    this.setData({ showRangePopup: false });
  },

  onRangeOptionTap(e) {
    const key = e.currentTarget.dataset.key;
    this.closeRangePopup();
    if (key === 'pickup') {
      this.switchToPickup();
    } else if (key === 'express') {
      this.switchToExpress();
    } else if (key === 'merchant') {
      this.useMerchantDelivery();
    } else if (key === 'address') {
      setTimeout(() => {
        this.chooseAddress();
      }, 120);
    }
  },

  // 切换为商家配送（复用当前已测距结果）
  useMerchantDelivery() {
    if (this.data.shopInfo.merchantDeliveryEnabled === false) {
      wx.showToast({ title: '本店暂未开通同城配送', icon: 'none' });
      return;
    }
    this.setData(Object.assign({
      deliveryType: 2
    }, this.resolvePayPatch(2, this.data.shopInfo)), () => {
      this.calcTotal();
      this.loadTimeOptions();
    });
  },

  preventBubble() {},

  // 切换为到店自取
  switchToPickup() {
    this.setData(Object.assign({
      deliveryType: 1,
      distanceCheckResult: null
    }, this.resolvePayPatch(1, this.data.shopInfo)), () => {
      this.calcTotal();
      this.loadTimeOptions();
    });
  },

  // 切换为快递配送（未开通时提示）
  switchToExpress() {
    const { shopInfo, selectedAddress } = this.data;
    if (shopInfo.expressEnabled !== 1) {
      wx.showToast({ title: '本店暂未开通快递配送', icon: 'none' });
      return;
    }
    this.setData(Object.assign({
      deliveryType: 4,
      distanceCheckResult: null
    }, this.resolvePayPatch(4, shopInfo)), () => {
      this.calcTotal();
      this.loadTimeOptions();
      if (selectedAddress) {
        this.checkExpressFee(selectedAddress);
      }
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
    // 支付方式由掌柜按「配送方式」单独配置，未开启时不可选
    if (val === 1 && !this.data.payWxpayEnabled) {
      wx.showToast({ title: '当前配送方式暂未开通微信线上支付', icon: 'none' });
      return;
    }
    if (val === 2 && !this.data.payCodEnabled) {
      wx.showToast({ title: '当前配送方式暂未开通货到付款', icon: 'none' });
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

    // 歇业中：直接拦截（按钮已置灰，这里再兜一层）
    if (this.data.shopClosed || isOrderBlocked(this.data.shopInfo)) {
      this.tipShopClosed(this.data.shopInfo && this.data.shopInfo.statusTip);
      return;
    }

    // 本地校验通过后统一走「后端下单前校验 → 提交订单」链路
    this.submitOrderPayload();
  },

  /** 歇业时返回（无上一页则回首页切换门店） */
  onBackFromClosed() {
    const pages = getCurrentPages();
    if (pages.length > 1) {
      wx.navigateBack();
    } else {
      wx.switchTab({ url: '/pages/index/index' });
    }
  },

  /** 歇业提示 */
  tipShopClosed(message) {
    wx.showModal({
      title: '门店已歇业',
      content: message || '该门店当前处于歇业状态，暂不接单。可返回切换其他门店或稍后再来～',
      showCancel: false,
      confirmText: '我知道了'
    });
  },

  /** 真正提交订单 */
  submitOrderPayload() {
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
      if (distanceCheckResult && !distanceCheckResult.canDeliver) {
        // 超出配送范围：直接弹出可选处理方式（到店自取 / 快递配送 / 更换地址）
        this.showRangeActions();
        return;
      }
      if (shortAmount > 0) {
        wx.showToast({ title: `未达到起送金额，还差 ¥${shortAmount} 起送`, icon: 'none' });
        return;
      }
      if (isSubmitDisabled) {
        wx.showToast({ title: '当前地址暂不可配送，请更换地址后重试', icon: 'none' });
        return;
      }
    } else if (deliveryType === 4) {
      // 快递配送：必须有收货地址，且地址不能命中「停发地区」
      if (!selectedAddress) {
        wx.showToast({ title: '请选择收货地址', icon: 'none' });
        return;
      }
      const expressResult = this.data.expressCheckResult;
      if (expressResult && !expressResult.canDeliver) {
        wx.showToast({ title: expressResult.message || '该地址暂不支持快递配送', icon: 'none' });
        return;
      }
    }

    // 2.1 期望送达/自取时间校验：有可预约档位时必须选择
    if (deliveryType !== 4 && this.data.timeOptionDays.length > 0 && !this.data.selectedTimeValue) {
      wx.showToast({
        title: deliveryType === 1 ? '请选择期望自取时间' : '请选择期望送达时间',
        icon: 'none'
      });
      return;
    }

    // 2.2 支付方式校验：必须是当前配送方式下掌柜已开启的支付方式
    const { payType, payWxpayEnabled, payCodEnabled } = this.data;
    if (!payWxpayEnabled && !payCodEnabled) {
      wx.showToast({ title: '当前配送方式暂无可用的支付方式', icon: 'none' });
      return;
    }
    if (!payType
      || (payType === 1 && !payWxpayEnabled)
      || (payType === 2 && !payCodEnabled)) {
      wx.showToast({ title: '请选择当前配送方式支持的支付方式', icon: 'none' });
      return;
    }

    // 3. 构建下单请求 DTO
    let postData = {
      shopId: this.data.shopId,
      deliveryType,
      payType: this.data.payType,
      remark: this.data.remark,
      pickupName: deliveryType === 1 ? pickupName : '',
      pickupPhone: deliveryType === 1 ? pickupPhone : '',
      // 期望送达/自取时间（快递配送不选，传 null 由后端忽略）
      expectedTime: deliveryType !== 4 ? (this.data.selectedTimeValue || null) : null,
      // 商家配送与快递配送都需要收货地址
      addressId: (deliveryType === 2 || deliveryType === 4) && selectedAddress ? selectedAddress.id : null
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

    // 4. 提交前调用后端「下单前统一校验」：
    //    商品/分类是否被删除、门店是否歇业、配送范围与起送价、商品价格与规格、
    //    期望送达时间、配送费/快递费与实付金额是否与页面展示一致
    //    注意：页面金额尚未算出（如商品未加载完）时不传快照，避免误判为金额不一致
    const checkData = Object.assign({}, postData);
    const pageDeliveryFee = Number(this.data.deliveryFee) || 0;
    if (deliveryType === 1 || pageDeliveryFee > 0) {
      checkData.deliveryFee = pageDeliveryFee;
    }
    if (Number(this.data.goodsAmount) > 0) {
      checkData.goodsAmount = Number(this.data.goodsAmount);
    }
    if (Number(this.data.payAmount) > 0) {
      checkData.payAmount = Number(this.data.payAmount);
    }

    if (this.data.isCartCheckout) {
      checkData.items = this.data.cartItems.map(item => {
        const itemData = {
          productId: item.productId,
          quantity: item.quantity,
          specInfo: item.specInfo
        };
        if (Number(item.price) > 0) {
          itemData.price = Number(item.price);
        }
        return itemData;
      });
    } else {
      const clientPrice = Number(this.data.product && this.data.product.price);
      if (clientPrice > 0) {
        checkData.price = clientPrice;
      }
    }

    this.setData({ submitting: true });
    wx.showLoading({ title: '正在校验订单...' });

    request.post('/order/precheck', checkData, Object.assign({ silent: true }, this.authHeader())).then((check) => {
      if (check && check.canOrder === false) {
        wx.hideLoading();
        this.setData({ submitting: false });
        this.handlePrecheckFail(check);
        return;
      }
      this.doCreateOrder(postData);
    }, () => {
      // 校验接口异常时不阻断下单，最终由 /order/create 兜底校验
      this.doCreateOrder(postData);
    });
  },

  /** 校验通过后真正提交订单 */
  doCreateOrder(postData) {
    wx.showLoading({ title: '正在提交订单...' });

    request.post('/order/create', postData, this.authHeader()).then((order) => {
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
    }).catch(() => {
      wx.hideLoading();
      this.setData({ submitting: false });
    });
  },

  /** 后端下单校验未通过：按校验项编码给出对应处理 */
  handlePrecheckFail(check) {
    const code = check.code || '';

    // 门店歇业：沿用原歇业整页拦截样式
    if (code === 'SHOP_CLOSED' || code === 'SHOP_NOT_FOUND') {
      this.setData({
        shopClosed: true,
        'shopInfo.displayStatus': 3,
        'shopInfo.statusText': '歇业中',
        'shopInfo.statusTip': check.message || '门店已歇业，暂不接单'
      });
      this.tipShopClosed(check.message);
      return;
    }

    // 支付方式不可用（掌柜端刚调整了该配送方式支持的支付方式）：刷新门店配置后让顾客重选
    if (code === 'PAY_TYPE_UNAVAILABLE' || code === 'PAY_TYPE_INVALID') {
      this.loadShopInfo(this.data.shopId);
      wx.showModal({
        title: '支付方式不可用',
        content: check.message || '该支付方式已被门店关闭，请重新选择支付方式后提交',
        showCancel: false,
        confirmText: '我知道了'
      });
      return;
    }

    // 商品信息（价格/规格/上下架/分类/库存）发生变化时，提供一键刷新入口
    const refreshCodes = [
      'PRICE_CHANGED', 'SPEC_CHANGED', 'PRODUCT_DELETED', 'PRODUCT_OFF_SALE',
      'CATEGORY_DELETED', 'CATEGORY_DISABLED', 'STOCK_NOT_ENOUGH',
      'AMOUNT_CHANGED', 'DELIVERY_FEE_CHANGED'
    ];
    const needRefresh = refreshCodes.indexOf(code) > -1;

    wx.showModal({
      title: '订单校验未通过',
      content: check.message || '订单信息已发生变化，请刷新后重新提交',
      showCancel: needRefresh,
      confirmText: needRefresh ? '刷新商品' : '我知道了',
      cancelText: '返回修改',
      success: (res) => {
        if (needRefresh && res.confirm) {
          this.syncCartFromPrecheck(check);
        }
      }
    });
  },

  /** 用后端校验结果刷新本地购物车（价格/库存/失效商品） */
  syncCartFromPrecheck(check) {
    if (!this.data.isCartCheckout) {
      // 单品直购：重新拉取商品详情刷新价格与规格
      if (this.data.productId) {
        this.loadProduct(this.data.productId);
      }
      wx.showToast({ title: '商品信息已刷新，请确认后重新提交', icon: 'none' });
      return;
    }

    const results = (check && check.items) || [];
    const resultMap = {};
    results.forEach((r) => {
      if (r && r.productId) {
        resultMap[`${r.productId}__${r.specInfo || ''}`] = r;
      }
    });

    const list = cart.getCart(this.data.shopId);
    const nextList = [];
    let removedCount = 0;
    let changedCount = 0;

    list.forEach((item) => {
      const r = resultMap[`${item.productId}__${item.specInfo || ''}`];
      if (!r) {
        nextList.push(item);
        return;
      }
      // 商品已删除 / 已下架 / 分类已失效：直接从购物车移除
      if (r.purchasable === false) {
        removedCount += 1;
        return;
      }

      const nextItem = Object.assign({}, item);
      if (r.price !== undefined && r.price !== null && !isNaN(Number(r.price))) {
        const price = Number(r.price);
        if (Number(nextItem.price) !== price) changedCount += 1;
        nextItem.price = price.toFixed(2);
      }
      if (r.stock !== undefined && r.stock !== null && !isNaN(Number(r.stock))) {
        const stock = Number(r.stock);
        nextItem.stock = stock;
        if (Number(nextItem.quantity) > stock) {
          nextItem.quantity = Math.max(1, stock);
        }
      }
      nextItem.subtotal = (Number(nextItem.price) * Number(nextItem.quantity || 1)).toFixed(2);
      nextList.push(nextItem);
    });

    cart.saveCart(this.data.shopId, nextList);

    this.setData({ cartItems: nextList }, () => {
      this.calcTotal();

      if (nextList.length === 0) {
        wx.showModal({
          title: '购物车已失效',
          content: '购物车中的商品已全部下架或删除，请返回重新选择商品',
          showCancel: false,
          confirmText: '我知道了',
          success: () => wx.navigateBack()
        });
        return;
      }

      const tips = [];
      if (removedCount > 0) tips.push(`已移除 ${removedCount} 件失效商品`);
      if (changedCount > 0) tips.push('商品价格已更新');
      wx.showToast({
        title: `${tips.length ? tips.join('，') : '商品信息已刷新'}，请确认后重新提交`,
        icon: 'none',
        duration: 2500
      });
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
    request.post('/payment/wx/create-params', { orderId }, this.authHeader()).then((payParams) => {
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
              request.post(`/payment/wx/mock-notify/${orderId}`, {}, this.authHeader()).then(() => {
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
