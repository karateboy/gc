<template>
  <div>
    <Row>
      <Card>
        <Form :model="formItem" :label-width="80">
          <FormItem label="選擇器">
            <Select v-model="formItem.monitor" filterable>
              <Option v-for="item in monitorList" :value="item._id" :key="item._id">{{ item.dp_no }}</Option>
            </Select>
          </FormItem>
          <FormItem label="測項">
            <Select v-model="formItem.monitorTypes" filterable multiple>
              <Option
                v-for="item in monitorTypeList"
                :value="item._id"
                :key="item._id"
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
    </Row>
    <Row>
      <Card v-if="display">
        <Table :columns="columns" :data="rows">
          <template slot-scope="{ row }" slot="name">
            <strong>{{ row.name }}</strong>
          </template>
          <template slot-scope="{ row, index }" slot="action">
            <Button
              type="primary"
              size="small"
              style="margin-right: 5px"
              @click="showPdfReport(index)"
            >報表</Button>
          </template>
        </Table>
      </Card>
    </Row>
  </div>
</template>
<style scoped>
</style>
<script>
import moment from "moment";
import config from "@/config";
const baseUrl =
  process.env.NODE_ENV === "development"
    ? config.baseUrl.dev
    : config.baseUrl.pro;

import { getMonitors, getMonitorTypes, getHistoryData } from "@/api/data";
export default {
  name: "historyData",
  mounted() {
    getMonitors()
      .then(resp => {
        this.monitorList.splice(0, this.monitorList.length);
        for (let mt of resp.data) {
          this.monitorList.push(mt);
        }
      })
      .catch(err => {
        alert(err);
      });
    // Init monitorTypeList
    getMonitorTypes()
      .then(resp => {
        this.monitorTypeList.splice(0, this.monitorTypeList.length);
        for (let mt of resp.data) {
          this.monitorTypeList.push(mt);
        }
      })
      .catch(err => {
        alert(err);
      });
  },
  data() {
    return {
      monitorList: [],
      monitorTypeList: [],
      formItem: {
        monitor: "",
        monitorTypes: [],
        dateRange: "",
        start: undefined,
        end: undefined
      },
      display: false,
      showPdf: false,
      pdfUrl: "",
      columns: [],
      rows: []
    };
  },
  computed: {},
  methods: {
    query() {
      this.display = true;
      this.formItem.start = this.formItem.dateRange[0].getTime();
      this.formItem.end = this.formItem.dateRange[1].getTime();
      getHistoryData({
        monitor: this.formItem.monitor,
        monitorTypes: encodeURIComponent(this.formItem.monitorTypes.join(",")),
        start: this.formItem.start,
        end: this.formItem.end
      })
        .then(resp => {
          const ret = resp.data;
          this.columns.splice(0, this.columns.length);
          this.rows.splice(0, this.rows.length);
          this.columns.push({
            title: "日期",
            key: "date",
            sortable: true
          });
          for (let i = 0; i < ret.columnNames.length; i++) {
            let col = {
              title: ret.columnNames[i],
              key: `col${i}`,
              sortable: true
            };
            this.columns.push(col);
          }
          //setup for report column
          this.columns.push({
            title: "動作",
            slot: "action",
            width: 150,
            align: "center"
          });
          for (let row of ret.rows) {
            let rowData = {
              date: new moment(row.date).format("lll"),
              cellClassName: {}
            };
            for (let c = 0; c < row.cellData.length; c++) {
              let key = `col${c}`;
              rowData[key] = row.cellData[c].v;
              rowData.cellClassName[key] = row.cellData[c].cellClassName;
              if (baseUrl.length != 0)
                rowData.pdfUrl = `${baseUrl}pdfReport/${row.pdfReport}`;
              else rowData.pdfUrl = `pdfReport/${row.pdfReport}`;
            }
            this.rows.push(rowData);
          }
        })
        .catch(err => {
          alert(err);
        });
    },
    showPdfReport(idx) {
      let url = this.rows[idx].pdfUrl;
      window.open(url);
    }
  }
};
</script>