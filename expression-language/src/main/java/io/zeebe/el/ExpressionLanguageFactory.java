/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.el;

import io.zeebe.el.impl.FeelExpressionLanguage;
import io.zeebe.util.sched.clock.ActorClock;

/** The entry point to create the default {@link ExpressionLanguage}. */
public class ExpressionLanguageFactory {

  /**
   * Using the broker's default clock for temporal expression.
   *
   * @return a new instance of the {@link ExpressionLanguage}
   */
  public static ExpressionLanguage createExpressionLanguage() {
    return createExpressionLanguage(ActorClock.current());
  }

  /**
   * @param clock the clock that is used for temporal expressions
   * @return a new instance of the {@link ExpressionLanguage}
   */
  public static ExpressionLanguage createExpressionLanguage(final ActorClock clock) {
    return new FeelExpressionLanguage(clock);
  }
}
