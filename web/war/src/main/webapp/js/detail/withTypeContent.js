
define([
    'util/vertex/formatters',
    'util/withDataRequest',
    './toolbar/toolbar',
    'require'
], function(F, withDataRequest, Toolbar, require) {
    'use strict';

    return withTypeContent;

    function withTypeContent() {

        withDataRequest.call(this);

        this._promisesToCancel = [];

        this.defaultAttrs({
            deleteFormSelector: '.delete-form'
        });

        this.after('teardown', function() {
            this.cancel();
            this.$node.empty();
        });

        this.before('initialize', function(node) {
            var self = this;

            $(node).removeClass('custom-entity-image')

            this.around('dataRequest', function(func) {
                var promise = func.apply(this, Array.prototype.slice.call(arguments, 1))

                if (promise.cancel) {
                    this._promisesToCancel.push(promise);
                }

                promise.then(function() {
                    self.trigger('finishedLoadingTypeContent');
                })

                return promise;
            })
        });

        this.after('initialize', function() {
            var self = this,
                previousConcept = this.attr.data.properties && F.vertex.concept(this.attr.data),
                previousConceptId = previousConcept && previousConcept.id;

            this.on('addNewProperty', this.onAddNewProperty);
            this.on('addNewComment', this.onAddNewComment);
            this.on('deleteItem', this.onDeleteItem);
            this.on('openFullscreen', this.onOpenFullscreen);
            this.on('openSourceUrl', this.onOpenSourceUrl);
            this.on('maskWithOverlay', this.onMaskWithOverlay);

            this.on('addProperty', this.redirectToPropertiesComponent);
            this.on('deleteProperty', this.redirectToPropertiesComponent);

            this.debouncedConceptTypeChange = _.debounce(this.debouncedConceptTypeChange.bind(this), 500);
            this.on(document, 'verticesUpdated', function(event, data) {
                if (data && data.vertices) {
                    var current = _.findWhere(data.vertices, { id: this.attr.data.id }),
                        concept = current && F.vertex.concept(current);

                    if (previousConceptId && concept && concept.id !== previousConceptId) {
                        self.debouncedConceptTypeChange(current);
                    }
                }
            });
        });

        this.redirectToPropertiesComponent = function(event, data) {
            if ($(event.target).closest('.comments').length) {
                return;
            }

            if ($(event.target).closest('.properties').length === 0) {
                event.stopPropagation();

                var properties = this.$node.find('.properties');
                if (properties.length) {
                    _.defer(function() {
                        properties.trigger(event.type, data);
                    })
                } else {
                    throw new Error('Unable to redirect properties request', event.type, data);
                }
            }
        };

        this.onOpenSourceUrl = function(event, data) {
            window.open(data.sourceUrl);
        };

        this.onAddNewProperty = function(event) {
            this.trigger(this.select('propertiesSelector'), 'editProperty');
        };

        this.onAddNewComment = function(event) {
            this.trigger(this.select('commentsSelector'), 'editComment');
        };

        this.onDeleteItem = function(event) {
            var self = this,
                $container = this.select('deleteFormSelector');

            if ($container.length === 0) {
                $container = $('<div class="delete-form"></div>').insertBefore(
                    this.select('propertiesSelector')
                );
            }

            require(['./dropdowns/deleteForm/deleteForm'], function(DeleteForm) {
                var node = $('<div class="underneath"></div>').appendTo($container);
                DeleteForm.attachTo(node, {
                    data: self.attr.data
                });
            });
        };

        this.onOpenFullscreen = function(event, data) {
            var viewing = this.attr.data,
                vertices = data && data.vertices ?
                    data.vertices :
                    _.isObject(viewing) && viewing.vertices ?
                    viewing.vertices :
                    viewing,
                url = F.vertexUrl.url(
                _.isArray(vertices) ? vertices : [vertices],
                visalloData.currentWorkspaceId
            );
            window.open(url);
        };

        this.onMaskWithOverlay = function(event, data) {
            event.stopPropagation();

            if (data.done) {
                this.$node.find('.detail-overlay').remove();
            } else {
                $('<div>')
                    .addClass('detail-overlay')
                    .toggleClass('detail-overlay-loading', data.loading)
                    .append($('<h1>').text(data.text))
                    .appendTo(this.$node);
            }
        };

        this.debouncedConceptTypeChange = function(vertex) {
            this.trigger(document, 'selectObjects', {
                vertices: [vertex],
                options: {
                    forceSelectEvenIfSame: true
                }
            });
        };

        this.selectionHistory = function() {
            if ('selectedObjectsStack' in visalloData) {
                var menus = [],
                    stack = visalloData.selectedObjectsStack;

                for (var i = stack.length - 1; i >= 0; i--) {
                    var s = stack[i];
                    menus.push({
                        title: s.title,
                        cls: 'history-item',
                        event: 'selectObjects',
                        eventData: {
                            vertexIds: s.vertexIds,
                            edgeIds: s.edgeIds,
                            options: {
                                ignoreMultipleSelectionOverride: true
                            }
                        }
                    });
                }
                if (menus.length) {
                    menus.splice(0, 0, Toolbar.ITEMS.DIVIDER, {
                        title: 'Previously Viewed',
                        cls: 'disabled'
                    });
                }
                return menus;
            }
        };

        this.sourceUrlToolbarItem = function() {
            var sourceUrl = _.findWhere(this.attr.data.properties, { name: 'http://visallo.org#sourceUrl' });

            if (sourceUrl) {
                return {
                    title: i18n('detail.toolbar.open.source_url'),
                    subtitle: i18n('detail.toolbar.open.source_url.subtitle'),
                    event: 'openSourceUrl',
                    eventData: {
                        sourceUrl: sourceUrl.value
                    }
                };
            }
        };

        this.cancel = function() {
            _.invoke(this._promisesToCancel, 'cancel');
            this._promisesToCancel.length = 0;
        };
    }
});
