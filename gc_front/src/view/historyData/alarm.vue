<template>
  <div>
    <Row>
      <Card>
        <Form ref="alarm" :model="formItem" :rules="rules" :label-width="80">
          <FormItem label="資料區間" prop="dateRange">
            <DatePicker
              type="datetimerange"
              format="yyyy-MM-dd HH:mm"
              placeholder="選擇資料區間"
              style="width: 300px"
              v-model="formItem.dateRange"
            ></DatePicker>
          </FormItem>
          <FormItem>
            <Button type="primary" icon="ios-search" @click="handleSubmit">查詢</Button>
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
        </Table>
      </Card>
    </Row>
  </div>
</template>
<style scoped>
</style>
<script>
import moment from 'moment';
import config from '@/config';

import { getAlarm } from '@/api/data';
const baseUrl =
  process.env.NODE_ENV === 'development'
    ? config.baseUrl.dev
    : config.baseUrl.pro;
export default {
  name: 'alarm',
  mounted() {},
  data() {
    return {
      formItem: {
        dateRange: [
          moment()
            .subtract(2, 'days')
            .toDate(),
          moment().toDate()
        ],
        start: undefined,
        end: undefined
      },
      rules: {
        dateRange: [
          {
            required: true,
            type: 'array',
            min: 2,
            message: '請選擇資料範圍',
            trigger: 'change'
          }
        ]
      },
      display: false,
      showPdf: false,
      pdfUrl: '',
      columns: [],
      rows: []
    };
  },
  computed: {},
  methods: {
    handleSubmit() {
      this.$refs.alarm.validate(valid => {
        if (valid) {
          this.query();
        }
      });
    },
    query() {
      this.display = true;
      getAlarm({
        start: this.formItem.dateRange[0].getTime(),
        end: this.formItem.dateRange[1].getTime()
      })
        .then(resp => {
          const ret = resp.data;
          this.columns.splice(0, this.columns.length);
          this.rows.splice(0, this.rows.length);
          this.columns.push({
            title: '日期',
            key: 'date',
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
          for (let row of ret.rows) {
            let rowData = {
              date: new moment(row.date).format('lll'),
              cellClassName: {}
            };
            for (let c = 0; c < row.cellData.length; c++) {
              let key = `col${c}`;
              rowData[key] = row.cellData[c].v;
              rowData.cellClassName[key] = row.cellData[c].cellClassName;
              if (baseUrl.length !== 0) {
                rowData.pdfUrl = `${baseUrl}pdfReport/${row.pdfReport}`;
              } else rowData.pdfUrl = `pdfReport/${row.pdfReport}`;
            }
            this.rows.push(rowData);
          }
        })
        .catch(err => {
          alert(err);
        });
    }
  }
};
</script>
