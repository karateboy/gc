// The Vue build version to load with the `import` command
// (runtime-only or standalone) has been set in webpack.base.conf with an alias.
import Vue from 'vue'
import App from './App'
import router from './router'
import store from './store'
import iView from 'iview'
import i18n from '@/locale'
import config from '@/config'
import importDirective from '@/directive'
import { directive as clickOutside } from 'v-click-outside-x'
import installPlugin from '@/plugin'
import '@/assets/icons/iconfont.css'
import TreeTable from 'tree-table-vue'
import VOrgTree from 'v-org-tree'
import 'v-org-tree/dist/v-org-tree.css'
import './index.less'
import axios from 'axios'
import highcharts from 'highcharts'
import moment from 'moment'
import VueNativeSock from 'vue-native-websocket'

axios.defaults.baseURL = process.env.NODE_ENV === 'development' ? config.baseUrl.dev : config.baseUrl.pro;
axios.defaults.withCredentials = true

moment.locale('zh-Tw')
highcharts.setOptions({
  global: {
    useUTC: false
  },
  lang: {
    contextButtonTitle: '圖表功能表',
    downloadJPEG: '下載JPEG',
    downloadPDF: '下載PDF',
    downloadPNG: '下載PNG',
    downloadSVG: '下載SVG',
    drillUpText: '回到{series.name}.',
    noData: '無資料',
    months: [
      '1月',
      '2月',
      '3月',
      '4月',
      '5月',
      '6月',
      '7月',
      '8月',
      '9月',
      '10月',
      '11月',
      '12月'
    ],
    printChart: '列印圖表',
    resetZoom: '重設放大區間',
    resetZoomTitle: '回到原圖大小',
    shortMonths: [
      '1月',
      '2月',
      '3月',
      '4月',
      '5月',
      '6月',
      '7月',
      '8月',
      '9月',
      '10月',
      '11月',
      '12月'
    ],
    weekdays: [
      '星期日',
      '星期一',
      '星期二',
      '星期三',
      '星期四',
      '星期五',
      '星期六'
    ]
  }
});

Vue.use(iView, {
  i18n: (key, value) => i18n.t(key, value)
})
Vue.use(TreeTable)
Vue.use(VOrgTree)
let host = process.env.NODE_ENV === 'development' ? 'localhost:9000' : `${location.host}`;
Vue.use(VueNativeSock, `ws://${host}/GcWebSocket`,
  {
    store,
    format: 'json',
    reconnection: true, // (Boolean) whether to reconnect automatically (false)
    reconnectionAttempts: 5, // (Number) number of reconnection attempts before giving up (Infinity),
    reconnectionDelay: 3000 // (Number) how long to initially wait before attempting a new (1000)
  })
/**
 * @description 注册admin内置插件
 */
installPlugin(Vue)
/**
 * @description 生产环境关掉提示
 */
Vue.config.productionTip = false
/**
 * @description 全局注册应用配置
 */
Vue.prototype.$config = config
/**
 * 注册指令
 */
importDirective(Vue)
Vue.directive('clickOutside', clickOutside)

/* eslint-disable no-new */
new Vue({
  el: '#app',
  router,
  i18n,
  store,
  render: h => h(App)
})
