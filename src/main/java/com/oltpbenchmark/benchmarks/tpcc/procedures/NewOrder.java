/*
 * Copyright 2020 by OLTPBenchmark Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.oltpbenchmark.benchmarks.tpcc.procedures;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.oltpbenchmark.api.SQLStmt;
import com.oltpbenchmark.benchmarks.tpcc.TPCCConfig;
import com.oltpbenchmark.benchmarks.tpcc.TPCCConstants;
import com.oltpbenchmark.benchmarks.tpcc.TPCCUtil;
import com.oltpbenchmark.benchmarks.tpcc.TPCCWorker;
import com.oltpbenchmark.benchmarks.tpcc.pojo.Stock;

public class NewOrder extends TPCCProcedure {

    private static final Logger LOG = LoggerFactory.getLogger(NewOrder.class);

    public final SQLStmt stmtGetCustSQL = new SQLStmt(
    """
        SELECT C_DISCOUNT, C_LAST, C_CREDIT
          FROM %s
         WHERE C_W_ID = ?
           AND C_D_ID = ?
           AND C_ID = ?
    """.formatted(TPCCConstants.TABLENAME_CUSTOMER));

    public final SQLStmt stmtGetWhseSQL = new SQLStmt(
    """
        SELECT W_TAX
          FROM %s
         WHERE W_ID = ?
    """.formatted(TPCCConstants.TABLENAME_WAREHOUSE));

    public final SQLStmt stmtGetDistSQL = new SQLStmt(
    """
        SELECT D_NEXT_O_ID, D_TAX
          FROM %s
         WHERE D_W_ID = ? AND D_ID = ?
    """.formatted(TPCCConstants.TABLENAME_DISTRICT));

    public final SQLStmt stmtInsertNewOrderSQL = new SQLStmt(
    """
        UPSERT INTO %s
         (NO_O_ID, NO_D_ID, NO_W_ID)
         VALUES ( ?, ?, ?)
    """.formatted(TPCCConstants.TABLENAME_NEWORDER));

    public final SQLStmt stmtUpdateDistSQL = new SQLStmt(
    """
        UPSERT INTO %s (D_W_ID, D_ID, D_NEXT_O_ID)
        VALUES (?, ?, ?)
    """.formatted(TPCCConstants.TABLENAME_DISTRICT));

    public final SQLStmt stmtInsertOOrderSQL = new SQLStmt(
    """
        UPSERT INTO %s
         (O_ID, O_D_ID, O_W_ID, O_C_ID, O_ENTRY_D, O_OL_CNT, O_ALL_LOCAL)
         VALUES (?, ?, ?, ?, ?, ?, ?)
    """.formatted(TPCCConstants.TABLENAME_OPENORDER));

    public final SQLStmt stmtGetItemSQL = new SQLStmt(
    """
        SELECT I_PRICE, I_NAME , I_DATA
          FROM %s
         WHERE I_ID = ?
    """.formatted(TPCCConstants.TABLENAME_ITEM));

    public final SQLStmt stmtGetStockSQL = new SQLStmt(
    """
        SELECT S_QUANTITY, S_DATA, S_DIST_01, S_DIST_02, S_DIST_03, S_DIST_04, S_DIST_05,
               S_DIST_06, S_DIST_07, S_DIST_08, S_DIST_09, S_DIST_10
          FROM %s
         WHERE S_I_ID = ?
           AND S_W_ID = ?
    """.formatted(TPCCConstants.TABLENAME_STOCK));

    public void run(Connection conn, Random gen, int terminalWarehouseID, int numWarehouses, int terminalDistrictLowerID, int terminalDistrictUpperID, TPCCWorker w) throws SQLException {

        int districtID = TPCCUtil.randomNumber(terminalDistrictLowerID, terminalDistrictUpperID, gen);
        int customerID = TPCCUtil.getCustomerID(gen);

        int numItems = TPCCUtil.randomNumber(5, 15, gen);
        int[] itemIDs = new int[numItems];
        int[] supplierWarehouseIDs = new int[numItems];
        int[] orderQuantities = new int[numItems];
        int allLocal = 1;

        for (int i = 0; i < numItems; i++) {
            itemIDs[i] = TPCCUtil.getItemID(gen);
            if (TPCCUtil.randomNumber(1, 100, gen) > 1) {
                supplierWarehouseIDs[i] = terminalWarehouseID;
            } else {
                do {
                    supplierWarehouseIDs[i] = TPCCUtil.randomNumber(1, numWarehouses, gen);
                }
                while (supplierWarehouseIDs[i] == terminalWarehouseID && numWarehouses > 1);
                allLocal = 0;
            }
            orderQuantities[i] = TPCCUtil.randomNumber(1, 10, gen);
        }

        // we need to cause 1% of the new orders to be rolled back.
        if (TPCCUtil.randomNumber(1, 100, gen) == 1) {
            itemIDs[numItems - 1] = TPCCConfig.INVALID_ITEM_ID;
        }

        newOrderTransaction(terminalWarehouseID, districtID, customerID, numItems, allLocal, itemIDs, supplierWarehouseIDs, orderQuantities, conn);

    }


    private void newOrderTransaction(int w_id, int d_id, int c_id,
                                     int o_ol_cnt, int o_all_local, int[] itemIDs,
                                     int[] supplierWarehouseIDs, int[] orderQuantities, Connection conn) throws SQLException {


        getCustomer(conn, w_id, d_id, c_id);

        getWarehouse(conn, w_id);

        int d_next_o_id = getDistrict(conn, w_id, d_id);

        updateDistrict(conn, w_id, d_id, d_next_o_id + 1);

        insertOpenOrder(conn, w_id, d_id, c_id, o_ol_cnt, o_all_local, d_next_o_id);

        insertNewOrder(conn, w_id, d_id, d_next_o_id);

        // TODO: insert VS upsert?

        String odreLinesql = "" +
            "declare $values as List<Struct<p1:Int32,p2:Int32,p3:Int32,p4:Int32,p5:Int32," +
            "p6:Timestamp,p7:Double,p8:Int32,p9:Double,p10:Utf8>>;\n" +
            "$mapper = ($row) -> (AsStruct(" +
            "$row.p1 as OL_W_ID, $row.p2 as OL_D_ID, $row.p3 as OL_O_ID, $row.p4 as OL_NUMBER, $row.p5 as OL_I_ID, " +
            "$row.p6 as OL_DELIVERY_D, $row.p7 as OL_AMOUNT, $row.p8 as OL_SUPPLY_W_ID, $row.p9 as OL_QUANTITY, " +
            "$row.p10 as OL_DIST_INFO));\n" +
            "upsert into " + TPCCConstants.TABLENAME_ORDERLINE + " select * from as_table(ListMap($values, $mapper));";

        String stockSql = "" +
            "declare $values as List<Struct<p1:Int32,p2:Int32,p3:Int32,p4:Double,p5:Int32,p6:Int32>>;\n" +
            "$mapper = ($row) -> (AsStruct(" +
            "$row.p1 as S_W_ID, $row.p2 as S_I_ID, $row.p3 as S_QUANTITY, " +
            "$row.p4 as S_YTD, $row.p5 as S_ORDER_CNT, " +
            "$row.p6 as S_REMOTE_CNT));\n" +
            "upsert into " + TPCCConstants.TABLENAME_STOCK + " select * from as_table(ListMap($values, $mapper));";

        try (PreparedStatement stmtUpdateStock = conn.prepareStatement(stockSql);
             PreparedStatement stmtInsertOrderLine = conn.prepareStatement(odreLinesql)) {

            for (int ol_number = 1; ol_number <= o_ol_cnt; ol_number++) {
                int ol_supply_w_id = supplierWarehouseIDs[ol_number - 1];
                int ol_i_id = itemIDs[ol_number - 1];
                int ol_quantity = orderQuantities[ol_number - 1];

                // this may occasionally error and that's ok!
                double i_price = getItemPrice(conn, ol_i_id);

                double ol_amount = ol_quantity * i_price;

                Stock s = getStock(conn, ol_supply_w_id, ol_i_id, ol_quantity);

                String ol_dist_info = getDistInfo(d_id, s);

                int idx = 1;
                stmtInsertOrderLine.setInt(idx++, w_id);
                stmtInsertOrderLine.setInt(idx++, d_id);
                stmtInsertOrderLine.setInt(idx++, d_next_o_id);
                stmtInsertOrderLine.setInt(idx++, ol_number);
                stmtInsertOrderLine.setInt(idx++, ol_i_id);
                stmtInsertOrderLine.setTimestamp(idx++, new Timestamp(System.currentTimeMillis()));
                stmtInsertOrderLine.setDouble(idx++, ol_amount);
                stmtInsertOrderLine.setInt(idx++, ol_supply_w_id);
                stmtInsertOrderLine.setInt(idx++, ol_quantity);
                stmtInsertOrderLine.setString(idx, ol_dist_info);
                stmtInsertOrderLine.addBatch();

                int s_remote_cnt_increment;

                if (ol_supply_w_id == w_id) {
                    s_remote_cnt_increment = 0;
                } else {
                    s_remote_cnt_increment = 1;
                }

                idx = 1;
                stmtUpdateStock.setInt(idx++, s.s_w_id);
                stmtUpdateStock.setInt(idx++, s.s_i_id);
                stmtUpdateStock.setInt(idx++, s.s_quantity);
                stmtUpdateStock.setDouble(idx++, s.s_ytd + ol_quantity);
                stmtUpdateStock.setInt(idx++, s.s_order_cnt + 1);
                stmtUpdateStock.setInt(idx++, s.s_remote_cnt + s_remote_cnt_increment);
                stmtUpdateStock.addBatch();
            }

            stmtInsertOrderLine.executeBatch();
            stmtInsertOrderLine.clearBatch();

            stmtUpdateStock.executeBatch();
            stmtUpdateStock.clearBatch();

        }

    }

    private String getDistInfo(int d_id, Stock s) {
        return switch (d_id) {
            case 1 -> s.s_dist_01;
            case 2 -> s.s_dist_02;
            case 3 -> s.s_dist_03;
            case 4 -> s.s_dist_04;
            case 5 -> s.s_dist_05;
            case 6 -> s.s_dist_06;
            case 7 -> s.s_dist_07;
            case 8 -> s.s_dist_08;
            case 9 -> s.s_dist_09;
            case 10 -> s.s_dist_10;
            default -> null;
        };
    }

    private Stock getStock(Connection conn, int ol_supply_w_id, int ol_i_id, int ol_quantity) throws SQLException {
        try (PreparedStatement stmtGetStock = this.getPreparedStatement(conn, stmtGetStockSQL)) {
            stmtGetStock.setInt(1, ol_i_id);
            stmtGetStock.setInt(2, ol_supply_w_id);
            try (ResultSet rs = stmtGetStock.executeQuery()) {
                if (!rs.next()) {
                    throw new RuntimeException("S_I_ID=" + ol_i_id + " not found!");
                }
                Stock s = new Stock();
                s.s_quantity = rs.getInt("S_QUANTITY");
                s.s_dist_01 = rs.getString("S_DIST_01");
                s.s_dist_02 = rs.getString("S_DIST_02");
                s.s_dist_03 = rs.getString("S_DIST_03");
                s.s_dist_04 = rs.getString("S_DIST_04");
                s.s_dist_05 = rs.getString("S_DIST_05");
                s.s_dist_06 = rs.getString("S_DIST_06");
                s.s_dist_07 = rs.getString("S_DIST_07");
                s.s_dist_08 = rs.getString("S_DIST_08");
                s.s_dist_09 = rs.getString("S_DIST_09");
                s.s_dist_10 = rs.getString("S_DIST_10");

                if (s.s_quantity - ol_quantity >= 10) {
                    s.s_quantity -= ol_quantity;
                } else {
                    s.s_quantity += -ol_quantity + 91;
                }

                return s;
            }
        }
    }

    private double getItemPrice(Connection conn, int ol_i_id) throws SQLException {
        try (PreparedStatement stmtGetItem = this.getPreparedStatement(conn, stmtGetItemSQL)) {
            stmtGetItem.setInt(1, ol_i_id);
            try (ResultSet rs = stmtGetItem.executeQuery()) {
                if (!rs.next()) {
                    // This is (hopefully) an expected error: this is an expected new order rollback
                    throw new UserAbortException("EXPECTED new order rollback: I_ID=" + ol_i_id + " not found!");
                }

                return rs.getDouble("I_PRICE");
            }
        }
    }

    private void insertNewOrder(Connection conn, int w_id, int d_id, int o_id) throws SQLException {
        try (PreparedStatement stmtInsertNewOrder = this.getPreparedStatement(conn, stmtInsertNewOrderSQL);) {
            stmtInsertNewOrder.setInt(1, o_id);
            stmtInsertNewOrder.setInt(2, d_id);
            stmtInsertNewOrder.setInt(3, w_id);
            int result = stmtInsertNewOrder.executeUpdate();

            if (result == 0) {
                LOG.warn("new order not inserted");
            }
        }
    }

    private void insertOpenOrder(Connection conn, int w_id, int d_id, int c_id, int o_ol_cnt, int o_all_local, int o_id) throws SQLException {
        try (PreparedStatement stmtInsertOOrder = this.getPreparedStatement(conn, stmtInsertOOrderSQL);) {
            stmtInsertOOrder.setInt(1, o_id);
            stmtInsertOOrder.setInt(2, d_id);
            stmtInsertOOrder.setInt(3, w_id);
            stmtInsertOOrder.setInt(4, c_id);
            stmtInsertOOrder.setTimestamp(5, new Timestamp(System.currentTimeMillis()));
            stmtInsertOOrder.setInt(6, o_ol_cnt);
            stmtInsertOOrder.setInt(7, o_all_local);

            int result = stmtInsertOOrder.executeUpdate();

            if (result == 0) {
                LOG.warn("open order not inserted");
            }
        }
    }

    private void updateDistrict(Connection conn, int w_id, int d_id, int d_next_o_id) throws SQLException {
        try (PreparedStatement stmtUpdateDist = this.getPreparedStatement(conn, stmtUpdateDistSQL)) {
            stmtUpdateDist.setInt(1, w_id);
            stmtUpdateDist.setInt(2, d_id);
            stmtUpdateDist.setInt(3, d_next_o_id);
            int result = stmtUpdateDist.executeUpdate();
            if (result == 0) {
                throw new RuntimeException("Error!! Cannot update next_order_id on district for D_ID=" + d_id + " D_W_ID=" + w_id);
            }
        }
    }

    private int getDistrict(Connection conn, int w_id, int d_id) throws SQLException {
        try (PreparedStatement stmtGetDist = this.getPreparedStatement(conn, stmtGetDistSQL)) {
            stmtGetDist.setInt(1, w_id);
            stmtGetDist.setInt(2, d_id);
            try (ResultSet rs = stmtGetDist.executeQuery()) {
                if (!rs.next()) {
                    throw new RuntimeException("D_ID=" + d_id + " D_W_ID=" + w_id + " not found!");
                }
                return rs.getInt("D_NEXT_O_ID");
            }
        }
    }

    private void getWarehouse(Connection conn, int w_id) throws SQLException {
        try (PreparedStatement stmtGetWhse = this.getPreparedStatement(conn, stmtGetWhseSQL)) {
            stmtGetWhse.setInt(1, w_id);
            try (ResultSet rs = stmtGetWhse.executeQuery()) {
                if (!rs.next()) {
                    throw new RuntimeException("W_ID=" + w_id + " not found!");
                }
            }
        }
    }

    private void getCustomer(Connection conn, int w_id, int d_id, int c_id) throws SQLException {
        try (PreparedStatement stmtGetCust = this.getPreparedStatement(conn, stmtGetCustSQL)) {
            stmtGetCust.setInt(1, w_id);
            stmtGetCust.setInt(2, d_id);
            stmtGetCust.setInt(3, c_id);
            try (ResultSet rs = stmtGetCust.executeQuery()) {
                if (!rs.next()) {
                    throw new RuntimeException("C_D_ID=" + d_id + " C_ID=" + c_id + " not found!");
                }
            }
        }
    }

}
