package com.showcase.interview.msorder.service;

import java.math.BigDecimal;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import com.showcase.interview.msorder.exception.CommitFailedException;
import com.showcase.interview.msorder.exception.DataNotFoundException;
import com.showcase.interview.msorder.exception.UndefinedException;
import com.showcase.interview.msorder.model.ReservedInventory;
import com.showcase.interview.msorder.model.Order;
import com.showcase.interview.msorder.model.OrderDetail;
import com.showcase.interview.msorder.model.PaymentRequest;
import com.showcase.interview.msorder.repository.OrderRepository;
import com.showcase.interview.msorder.utils.SuccessTemplateMessage;
import com.showcase.interview.msorder.utils.Util;

@Service
public class OrderService {

	
	public OrderService(RabbitTemplate rabbitTemplate) {
		super();
		this.rabbitTemplate = rabbitTemplate;
	}

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private OrderDetailService orderDetailService;
	
	@Autowired
	private InventoryRestService inventoryRestService;

	
	@Autowired
	private final RabbitTemplate rabbitTemplate;
	
	@Autowired
	private Util util;

	public Iterable<Order> getAll() {
		return orderRepository.findAll();
	}
	

	public Order createNew(Order newData) throws CommitFailedException, UndefinedException {
		try {
			newData.setCreated_at(util.getTimeNow());
			newData.setUpdated_at(util.getTimeNow());
			Order result = orderRepository.save(newData);
			BigDecimal totalAmount = BigDecimal.valueOf(0);
			for (OrderDetail orderDetail : newData.getOrderDetail()) {
				
				ReservedInventory itemBase = inventoryRestService.getPostWithUrlParameters(orderDetail.getItem_id());
				orderDetail.setBasePrice(itemBase.getPrice());
				orderDetail.setOrder(result);
				orderDetail.setTotal();
				totalAmount = totalAmount.add(orderDetail.getTotal());
				orderDetailService.createNew(orderDetail);
				
//				insert to queue to reserve QTY
				ReservedInventory newInventory = new ReservedInventory();
				newInventory.setQuantity(orderDetail.getQty());
				newInventory.setId(orderDetail.getItem_id());
				this.rabbitTemplate.convertSendAndReceive("ms-inventory-queue", newInventory);
			}
//			Insert to queue to request payment
			PaymentRequest newPaymentRequest =  new PaymentRequest();
			newPaymentRequest.setAmount(totalAmount);
			newPaymentRequest.setCurrency(newData.getCurrency());
			newPaymentRequest.setOrderId(result.getId());
			newPaymentRequest.setStatus("WAITING_FOR_PAYMENT");
			this.rabbitTemplate.convertSendAndReceive("ms-payment-queue", newPaymentRequest);
			result.setTotalAmount(totalAmount);
			return orderRepository.save(newData);
		} catch (Exception e) {
			if (e.getMessage().contains("Error while committing")) {
				throw new CommitFailedException();
			} else {
				throw new UndefinedException(e.toString());
			}
		}
	}

	public Order getById(long id) throws DataNotFoundException {
		return orderRepository.findById(id).orElseThrow(() -> new DataNotFoundException());
	}

	public Order updateById(Order updatedData, Long id) {

		return orderRepository.findById(id).map(data -> {
			updatedData.setId(id);
			updatedData.setCreated_at(data.getCreated_at());
			data = updatedData;

			data.setUpdated_at(util.getTimeNow());
			return orderRepository.save(data);
		}).orElseGet(() -> {
			updatedData.setCreated_at(util.getTimeNow());
			updatedData.setUpdated_at(util.getTimeNow());
			return orderRepository.save(updatedData);
		});
	}
	
	public Order updateStatusById(Order updatedData, Long id) {

		return orderRepository.findById(id).map(data -> {
			data.setStatus(updatedData.getStatus());
			data.setUpdated_at(util.getTimeNow());
			return orderRepository.save(data);
		}).orElseGet(() -> {
			updatedData.setCreated_at(util.getTimeNow());
			updatedData.setUpdated_at(util.getTimeNow());
			return orderRepository.save(updatedData);
		});
	}

	public ResponseEntity<Object> deleteById(long id) throws DataNotFoundException {

		try {
			if (orderRepository.findById(id) != null) {
				orderRepository.deleteById(id);
			}
		} catch (Exception e) {
			throw new DataNotFoundException();
		}
		return new ResponseEntity<Object>(new SuccessTemplateMessage(), HttpStatus.OK);
	}

}