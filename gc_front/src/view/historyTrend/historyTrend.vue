<template>
    <div>
        <Row>
            <Col>
                <Card>
                    <Form ref="historyTrend" :model="formItem" :rules="rules" :label-width="80">
                        <FormItem label="測項" prop="monitorTypes">
                            <Select v-model="formItem.monitorTypes" filterable multiple>
                                <Option v-for="item in monitorTypeList" :value="item.id" :key="item._id">{{ item.desp }}</Option>
                            </Select>
                        </FormItem>
                        <FormItem label="單位" prop="reportUnit">
                            <Select v-model="formItem.reportUnit" filterable>
                                <Option v-for="unit in reportUnit" :value="unit.id" :key="unit.id">{{ unit.desc }}</Option>
                            </Select>
                        </FormItem>
                        <FormItem label="圖表類型" prop="chartType">
                            <Select v-model="formItem.chartType" filterable>
                                <Option v-for="chart in chartType" :value="chart.type" :key="chart.type">{{ chart.desc }}</Option>
                            </Select>
                        </FormItem>

                        <FormItem label="資料區間" prop="dateRange">
                                <DatePicker type="datetimerange" format="yyyy-MM-dd HH:mm"
                                    placeholder="選擇資料區間" style="width: 300px"
                                    v-model="formItem.dateRange"></DatePicker>
                        </FormItem>
                        <FormItem>
                            <Button type="primary" @click="handleSubmit">查詢</Button>
                            <Button style="margin-left: 8px" @click="handleReset('historyTrend')">取消</Button>
                            <Button style="margin-left: 8px" icon="document" :disabled="!downloadable" @click="downloadExcel">下載Excel</Button>
                        </FormItem>
                    </Form>
                </Card>
            </Col>
        </Row>
        <Row>
            <Card v-if="display">
              <div id="reportDiv"></div>
            </Card>
        </Row>
    </div>
</template>
<style scoped>
</style>
<script>
import highcharts from 'highcharts'
import highchart_more from 'highcharts/highcharts-more'
import exporting from 'highcharts/modules/exporting'
import { getMonitorTypes, getHistoryData } from '@/api/data'

export default {
  name: 'historyTrend',
  mounted() {
    getMonitorTypes()
      .then(resp => {
        this.monitorTypeList.splice(0, this.monitorTypeList.length)
        for (let mt of resp.data) {
          this.monitorTypeList.push(mt)
        }
      })
      .catch(err => {
        alert(err)
      })
  },
  data() {
    return {
      monitorTypeList: [],
      reportUnit: [
        {
          id: 'Min',
          desc: '分'
        },
        {
          id: 'TenMin',
          desc: '十分'
        },
        {
          id: 'Hour',
          desc: '小時'
        },
        {
          id: 'Day',
          desc: '日'
        },
        {
          id: 'Month',
          desc: '月'
        },
        {
          id: 'Quarter',
          desc: '季'
        },
        {
          id: 'Year',
          desc: '年'
        }
      ],
      chartType: [
        {
          type: 'line',
          desc: '折線圖'
        },
        {
          type: 'spline',
          desc: '曲線圖'
        },
        {
          type: 'area',
          desc: '面積圖'
        },
        {
          type: 'areaspline',
          desc: '曲線面積圖'
        },
        {
          type: 'column',
          desc: '柱狀圖'
        },
        {
          type: 'scatter',
          desc: '點圖'
        },
        {
          type: 'boxplot',
          desc: '盒鬚圖'
        }
      ],
      formItem: {
        monitorTypes: [],
        dateRange: [],
        chartType: 'line',
        start: undefined,
        end: undefined
      },
      rules: {
        monitorTypes: [
          {
            required: true,
            type: 'array',
            min: 1,
            message: '至少選擇一個測項',
            trigger: 'change'
          }
        ],
        reportUnit: [{ required: true, message: '單位未填', trigger: 'blur' }],
        dateRange: [
          {
            required: true,
            type: 'array',
            min: 1,
            message: '請選擇資料範圍',
            trigger: 'change'
          }
        ]
      },
      display: false,
      query_url: ''
    }
  },
  computed: {
    downloadable() {
      return this.query_url.length !== 0
    }
  },
  methods: {
    handleSubmit() {
      this.$refs.historyTrend.validate(valid => {
        if (valid) {
          this.query()
        }
      })
    },
    handleReset(name) {
      this.$refs[name].resetFields()
    },
    downloadExcel() {
      let url = baseUrl() + `/Excel/HistoryTrend/${this.query_url}`
      window.open(url)
    },
    query() {
      this.display = true
      let monitors = encodeURIComponent(this.formItem.monitors.join(':'))
      let monitorTypes = encodeURIComponent(
        this.formItem.monitorTypes.join(':')
      )

      if (this.formItem.chartType === 'boxplot') {
        if (this.formItem.monitorTypes.length > 1) {
          alert('盒鬚圖只能選擇單一測項!')
          return
        }
      }
      let start = this.formItem.dateRange[0].getTime()
      let end = this.formItem.dateRange[1].getTime()
      this.query_url = `${monitors}/${monitorTypes}/${
        this.formItem.reportUnit
      }/${start}/${end}`

      let url_head = ''
      if (this.formItem.chartType !== 'boxplot') { url_head = '/JSON/HistoryTrend/' } else url_head = '/JSON/HistoryBoxplot/'

      axios
        .get(url_head + this.query_url)
        .then(resp => {
          const ret = resp.data
          if (this.formItem.chartType !== 'boxplot') {
            ret.chart = {
              type: this.formItem.chartType,
              zoomType: 'x',
              panning: true,
              panKey: 'shift',
              alignTicks: false
            }

            var pointFormatter = function() {
              var d = new Date(this.x)
              return d.toLocaleString() + ': ' + Math.round(this.y) + '度'
            }

            ret.colors = [
              '#7CB5EC',
              '#434348',
              '#90ED7D',
              '#F7A35C',
              '#8085E9',
              '#F15C80',
              '#E4D354',
              '#2B908F',
              '#FB9FA8',
              '#91E8E1',
              '#7CB5EC',
              '#80C535',
              '#969696'
            ]

            ret.tooltip = { valueDecimals: 2 }
            ret.legend = { enabled: true }
            ret.credits = {
              enabled: false,
              href: 'http://www.wecc.com.tw/'
            }
            ret.xAxis.type = 'datetime'
            ret.xAxis.dateTimeLabelFormats = {
              day: '%b%e日',
              week: '%b%e日',
              month: '%y年%b'
            }

            ret.plotOptions = {
              scatter: {
                tooltip: {
                  pointFormatter: pointFormatter
                }
              }
            }
          } else {
            // extra setting for boxplot
            console.log(ret)
          }
          highchart_more(highcharts)
          exporting(highcharts)
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
          })
          highcharts.chart('reportDiv', ret)
        })
        .catch(err => {
          alert(err)
        })
    }
  }
}
</script>
