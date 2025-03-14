<template>
  <div>
    <Row>
      <Col v-if="display">
        <br />
        <Table :columns="columns" :data="rows">
          <template slot-scope="{ row }" slot="name">
            <strong>{{ row.name }}</strong>
          </template>
          <template slot-scope="{ row, index }" slot="action">
            <table>
              <tbody>
              <tr>
                <td>
                  <Button
                    type="primary"
                    size="large"
                    @click="showPdfReport(index)"
                  >PDF
                  </Button
                  >
                </td>
              </tr>
              <tr></tr>
              </tbody>
            </table>
          </template>
        </Table>
      </Col>
    </Row>
  </div>
</template>
<style>
.ivu-table .text-danger {
  color: red;
}

.ivu-table .demo-table-info-cell-address {
  background-color: #187;
  color: #fff;
}
</style>
<script>
import moment from 'moment';
import config from '@/config';
import URI from 'urijs';

import {
  getCalibrationData,
  getGcList,
  getLast10CalibrationData,
  getLast10OldGcCalibrationData,
  getMonitors,
  getMonitorTypes,
} from '@/api/data';

const baseUrl =
  process.env.NODE_ENV === 'development'
    ? config.baseUrl.dev
    : config.baseUrl.pro;
export default {
  name: 'calibrationData',
  mounted() {
    this.query();
    this.timer = setTimeout(this.query, 30000);
  },
  computed: {
  },
  data() {
    return {
      monitorTypeList: [],
      display: false,
      columns: [],
      rows: [],
      timer: undefined,
    };
  },
  methods: {
    query() {
      this.display = true;
      getLast10OldGcCalibrationData()
        .then(resp => {
          const ret = resp.data;
          this.columns.splice(0, this.columns.length);
          this.rows.splice(0, this.rows.length);
          this.columns.push({
            title: '日期',
            key: 'date',
            sortable: true,
          });
          for (let i = 0; i < ret.columnNames.length; i++) {
            let col = {
              title: ret.columnNames[i],
              key: `col${i}`,
              sortable: true,
            };
            this.columns.push(col);
          }

          for (let row of ret.rows) {
            let rowData = {
              date: new moment(row.date).format('lll'),
              cellClassName: {},
            };
            for (let c = 0; c < row.cellData.length; c++) {
              let key = `col${c}`;
              rowData[key] = row.cellData[c].v;
              rowData.cellClassName[key] = row.cellData[c].cellClassName;
              if (baseUrl.length !== 0) {
                rowData.pdfUrl = `${baseUrl}pdfReport/${row.pdfReport}`;
                rowData.excelUrl = `${baseUrl}excelForm/${row.pdfReport}`;
              } else {
                rowData.pdfUrl = `pdfReport/${row.pdfReport}`;
                rowData.excelUrl = `${baseUrl}excelForm/${row.pdfReport}`;
              }
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
    },
  },
  destroyed() {
    clearTimeout(this.timer);
  },
};
</script>
