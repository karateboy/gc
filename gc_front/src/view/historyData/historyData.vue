<template>
  <div>
    <Row>
      <Col>
        <Card>
          <Form :model="formItem" :label-width="80">
            <FormItem label="測項">
              <Select v-model="formItem.monitorTypes" filterable multiple>
                <Option
                  v-for="item in monitorTypeList"
                  :value="item.id"
                  :key="item.id"
                >{{ item.desp }}</Option>
              </Select>
            </FormItem>
            <FormItem label="資料區間">
              <DatePicker
                type="datetimerange"
                format="yyyy-MM-dd HH:mm"
                placeholder="選擇資料區間"
                style="width: 300px"
                v-model="formItem.dateRange"
              ></DatePicker>
            </FormItem>
            <FormItem>
              <Button type="primary" @click="query">查詢</Button>
              <Button style="margin-left: 8px">取消</Button>
            </FormItem>
          </Form>
        </Card>
      </Col>
    </Row>
    <Row>
      <Col>
        <Card v-if="display">
          <Table :columns="columns" :data="rows"></Table>
        </Card>
      </Col>
    </Row>
  </div>
</template>
<style scoped>
</style>
<script>
import moment from 'moment'
import { getMonitorTypes, getHistoryData } from '@/api/data'
export default {
  name: 'historyData',
  mounted() {
    // Init monitorTypeList
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
      formItem: {
        monitorTypes: [],
        dateRange: '',
        start: undefined,
        end: undefined
      },
      display: false,
      columns: [],
      rows: []
    }
  },
  computed: {},
  methods: {
    query() {
      this.display = true
      this.formItem.start = this.formItem.dateRange[0].getTime()
      this.formItem.end = this.formItem.dateRange[1].getTime()
      getHistoryData({
        monitorTypes: encodeURIComponent(this.formItem.monitorTypes.join(',')),
        start: this.formItem.start,
        end: this.formItem.end
      }).then(resp => {
        const ret = resp.data
        this.columns.splice(0, this.columns.length)
        this.rows.splice(0, this.rows.length)
        this.columns.push({
          title: '日期',
          key: 'date',
          sortable: true
        })
        for (let i = 0; i < ret.columnNames.length; i++) {
          let col = {
            title: ret.columnNames[i],
            key: `col${i}`,
            sortable: true
          }
          this.columns.push(col)
        }
        for (let row of ret.rows) {
          let rowData = {
            date: new moment(row.date).format('lll'),
            cellClassName: {}
          }
          for (let c = 0; c < row.cellData.length; c++) {
            let key = `col${c}`
            rowData[key] = row.cellData[c].v
            rowData.cellClassName[key] = row.cellData[c].cellClassName
          }
          this.rows.push(rowData)
        }
      })
        .catch(err => {
          alert(err)
        })
    }
  }
}
</script>
