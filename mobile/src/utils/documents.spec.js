import { describe, it, expect } from 'vitest'
import { mergeDocuments } from './documents.js'
describe('document aggregation',()=>{
 it('deduplicates sales lines without multiplying order payments and sorts sources',()=>{
  const sales={records:[{order_id:1,order_no:'X1',goods_name:'戒指',actual_paid:800,remaining_amount:0,date:'2026-10-01 10:00'},{order_id:1,order_no:'X1',goods_name:'手链',actual_paid:800,remaining_amount:0,date:'2026-10-01 10:00'}]}
  const result=mergeDocuments(sales,[{processing_order_id:1,order_no:'J1',due_amount:100,create_time:'2026-10-02 10:00',status:'PENDING'}],{records:[{recycle_order_id:1,bill_no:'H1',total_amount:500,create_time:'2026-10-01 11:00'}]})
  expect(result.map(row=>row.type)).toEqual(['processing','recycle','sale'])
  expect(result[2].amount).toBe(800)
  expect(result[2].title).toBe('戒指、手链')
  expect(result[1].status).toBe('回收记录')
 })
 it('keeps missing amounts unknown and accepts empty responses',()=>{
  expect(mergeDocuments(null,{},undefined)).toEqual([])
  expect(mergeDocuments([{order_id:2}],[],[])[0].amount).toBeNull()
 })
 it('hides processing orders the operator deleted on mobile without touching other rows',()=>{
  const processing=[
   {processing_order_id:1,order_no:'J1',due_amount:100,create_time:'2026-10-02 10:00',status:'PICKED_UP',mobile_archived:1},
   {processing_order_id:2,order_no:'J2',due_amount:200,create_time:'2026-10-03 10:00',status:'PICKED_UP',mobile_archived:0},
   {processing_order_id:3,order_no:'J3',due_amount:300,create_time:'2026-10-04 10:00',status:'PROCESSING'}
  ]
  const result=mergeDocuments([],processing,[])
  expect(result.map(row=>row.no)).toEqual(['J3','J2'])
 })
})
