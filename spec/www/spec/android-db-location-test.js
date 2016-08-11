/* 'use strict'; */

var MYTIMEOUT = 20000;

var isWindows = /Windows /.test(navigator.userAgent); // Windows
var isAndroid = !isWindows && /Android/.test(navigator.userAgent);

var pluginScenarioList = [
  isAndroid ? 'Plugin-implementation-default' : 'Plugin',
  'Plugin-implementation-2'
];

//var pluginScenarioCount = isAndroid ? 2 : 1;
var pluginScenarioCount = 1;

var mytests = function() {

  for (var i=0; i<pluginScenarioCount; ++i) {

    describe(pluginScenarioList[i] + ': Android db directory test(s)', function() {
      var scenarioName = pluginScenarioList[i];
      var suiteName = scenarioName + ': ';
      // FUTURE TBD
      //var isImpl2 = (i === 1);

        it(suiteName + 'Create db file in Documents, check size, copy to default location, check copy, and delete original', function(done) {
          if (!isAndroid) pending('SKIP for iOS/Windows/WP8');

          var dbname = 'Create-db-in-documents-test.db';
          var copyname = 'copy-db-from-documents.db';

          window.resolveLocalFileSystemURL(cordova.file.dataDirectory, function(dataDirectoryEntry) {
            expect(dataDirectoryEntry).toBeDefined();

            expect(dataDirectoryEntry.isDirectory).toBe(true);

            dataDirectoryEntry.getDirectory('Documents', null, function(documentsDirectoryEntry) {
              expect(documentsDirectoryEntry).toBeDefined();
              expect(documentsDirectoryEntry.isDirectory).toBe(true);

              var createDatabaseDirectoryEntry = documentsDirectoryEntry;
              var db = window.sqlitePlugin.openDatabase({name: dbname, androidDatabaseLocation: createDatabaseDirectoryEntry.toURL()});

              db.transaction(function(tx) {
                tx.executeSql('DROP TABLE IF EXISTS tt');
                tx.executeSql('CREATE TABLE tt (data)');
                tx.executeSql('INSERT INTO tt VALUES (?)', ['test-value']);

              }, function(error) {
                // NOT EXPECTED:
                expect(false).toBe(true);
                expect(JSON.stringify(error)).toBe('---');
                // Close (PLUGIN) & finish:
                db.close(done, done);

              }, function() {
                db.close(function() {
                  createDatabaseDirectoryEntry.getFile(dbname, null, function(dbFileEntry) {
                    expect(dbFileEntry).toBeDefined();
                    expect(dbFileEntry.isFile).toBe(true);

                    dbFileEntry.file(function(f) {
                      expect(f).toBeDefined();
                      expect(f.size).toBeDefined();
                      expect(f.size).not.toBe(-1);
                      expect(f.size).not.toBe(0);
                      expect(f.size > 1000).toBe(true);

                      window.resolveLocalFileSystemURL(cordova.file.applicationStorageDirectory, function(storageDirectoryEntry) {
                        expect(storageDirectoryEntry).toBeDefined();
                        expect(storageDirectoryEntry.isDirectory).toBe(true);

                        storageDirectoryEntry.getDirectory('databases', null, function(dbDirectoryEntry) {
                          expect(dbDirectoryEntry).toBeDefined();

                          dbFileEntry.copyTo(dbDirectoryEntry, copyname, function(newFileEntry) {
                            expect(newFileEntry).toBeDefined();
                            expect(newFileEntry.isFile).toBe(true);

                            var db2 = window.sqlitePlugin.openDatabase({name: copyname, location: 'default'});

                            db2.transaction(function(tx) {
                              tx.executeSql('SELECT * FROM tt', [], function(tx_ignored, rs) {
                                expect(rs).toBeDefined();
                                expect(rs.rows).toBeDefined();
                                expect(rs.rows.length).toBe(1);
                                expect(rs.rows.item(0).data).toBeDefined();
                                expect(rs.rows.item(0).data).toBe('test-value');

                                var deleteOptions = {name: dbname, androidDatabaseLocation: createDatabaseDirectoryEntry.toURL()};
                                window.sqlitePlugin.deleteDatabase(deleteOptions, function() {
                                  createDatabaseDirectoryEntry.getFile(dbname, null, function(dbFileEntry) {
                                    // NOT EXPECTED - old database file is still available:
                                    expect(false).toBe(true);
                                    done();
                                  }, function(error) {
                                    // EXPECTED RESULT - old database file is gone:
                                    expect(true).toBe(true);
                                    done();
                                  });

                                }, function(error) {
                                  // NOT EXPECTED:
                                  expect(false).toBe(true);
                                  expect(JSON.stringify(error)).toBe('---');
                                  done();
                                });

                              });
                            }, function(error) {
                              // NOT EXPECTED:
                              expect(false).toBe(true);
                              expect(JSON.stringify(error)).toBe('---');
                              // Close (PLUGIN) & finish:
                              db.close(done, done);
                            });

                          }, function(error) {
                            // NOT EXPECTED:
                            expect(false).toBe(true);
                            expect(JSON.stringify(error)).toBe('---');
                            done();
                          });

                        }, function(error) {
                          // NOT EXPECTED:
                          expect(false).toBe(true);
                          expect(JSON.stringify(error)).toBe('---');
                          done();
                        });

                      }, function(error) {
                        // NOT EXPECTED:
                        expect(false).toBe(true);
                        expect(JSON.stringify(error)).toBe('---');
                        done();
                      });

                    }, function(error) {
                      // NOT EXPECTED:
                      expect(false).toBe(true);
                      expect(JSON.stringify(error)).toBe('---');
                      done();
                    });

                  }, function(error) {
                    // NOT EXPECTED:
                    expect(false).toBe(true);
                    expect(JSON.stringify(error)).toBe('---');
                    done();
                  });

                }, function(error) {
                  // NOT EXPECTED:
                  expect(false).toBe(true);
                  expect(JSON.stringify(error)).toBe('---');
                  done();
                });
              });

            }, function(error) {
              // NOT EXPECTED:
              expect(false).toBe(true);
              expect(JSON.stringify(error)).toBe('---');
              done();
            });

          }, function(error) {
            // NOT EXPECTED:
            expect(false).toBe(true);
            expect(JSON.stringify(error)).toBe('---');
            done();
          });

        }, MYTIMEOUT);

        it(suiteName + 'Create db file in external files directory, check size, copy to default location, check copy, and delete original', function(done) {
          if (!isAndroid) pending('SKIP for iOS/Windows/WP8');

          var dbname = 'Create-db-in-external-files-test.db';
          var copyname = 'copy-db-from-external-files.db';

          window.resolveLocalFileSystemURL(cordova.file.externalDataDirectory, function(externalDataDirectoryEntry) {
            expect(externalDataDirectoryEntry).toBeDefined();

            expect(externalDataDirectoryEntry.isDirectory).toBe(true);

              var createDatabaseDirectoryEntry = externalDataDirectoryEntry;
              var db = window.sqlitePlugin.openDatabase({name: dbname, androidDatabaseLocation: createDatabaseDirectoryEntry.toURL()});

              db.transaction(function(tx) {
                tx.executeSql('DROP TABLE IF EXISTS tt');
                tx.executeSql('CREATE TABLE tt (data)');
                tx.executeSql('INSERT INTO tt VALUES (?)', ['test-value']);

              }, function(error) {
                // NOT EXPECTED:
                expect(false).toBe(true);
                expect(JSON.stringify(error)).toBe('---');
                // Close (PLUGIN) & finish:
                db.close(done, done);

              }, function() {
                db.close(function() {
                  createDatabaseDirectoryEntry.getFile(dbname, null, function(dbFileEntry) {
                    expect(dbFileEntry).toBeDefined();
                    expect(dbFileEntry.isFile).toBe(true);

                    dbFileEntry.file(function(f) {
                      expect(f).toBeDefined();
                      expect(f.size).toBeDefined();
                      expect(f.size).not.toBe(-1);
                      expect(f.size).not.toBe(0);
                      expect(f.size > 1000).toBe(true);

                      window.resolveLocalFileSystemURL(cordova.file.applicationStorageDirectory, function(storageDirectoryEntry) {
                        expect(storageDirectoryEntry).toBeDefined();
                        expect(storageDirectoryEntry.isDirectory).toBe(true);

                        storageDirectoryEntry.getDirectory('databases', null, function(dbDirectoryEntry) {
                          expect(dbDirectoryEntry).toBeDefined();

                          dbFileEntry.copyTo(dbDirectoryEntry, copyname, function(newFileEntry) {
                            expect(newFileEntry).toBeDefined();
                            expect(newFileEntry.isFile).toBe(true);

                            var db2 = window.sqlitePlugin.openDatabase({name: copyname, location: 'default'});

                            db2.transaction(function(tx) {
                              tx.executeSql('SELECT * FROM tt', [], function(tx_ignored, rs) {
                                expect(rs).toBeDefined();
                                expect(rs.rows).toBeDefined();
                                expect(rs.rows.length).toBe(1);
                                expect(rs.rows.item(0).data).toBeDefined();
                                expect(rs.rows.item(0).data).toBe('test-value');

                                var deleteOptions = {name: dbname, androidDatabaseLocation: createDatabaseDirectoryEntry.toURL()};
                                window.sqlitePlugin.deleteDatabase(deleteOptions, function() {
                                  createDatabaseDirectoryEntry.getFile(dbname, null, function(dbFileEntry) {
                                    // NOT EXPECTED - old database file is still available:
                                    expect(false).toBe(true);
                                    done();
                                  }, function(error) {
                                    // EXPECTED RESULT - old database file is gone:
                                    expect(true).toBe(true);
                                    done();
                                  });

                                }, function(error) {
                                  // NOT EXPECTED:
                                  expect(false).toBe(true);
                                  expect(JSON.stringify(error)).toBe('---');
                                  done();
                                });

                              });
                            }, function(error) {
                              // NOT EXPECTED:
                              expect(false).toBe(true);
                              expect(JSON.stringify(error)).toBe('---');
                              // Close (PLUGIN) & finish:
                              db.close(done, done);
                            });

                          }, function(error) {
                            // NOT EXPECTED:
                            expect(false).toBe(true);
                            expect(JSON.stringify(error)).toBe('---');
                            done();
                          });

                        }, function(error) {
                          // NOT EXPECTED:
                          expect(false).toBe(true);
                          expect(JSON.stringify(error)).toBe('---');
                          done();
                        });

                      }, function(error) {
                        // NOT EXPECTED:
                        expect(false).toBe(true);
                        expect(JSON.stringify(error)).toBe('---');
                        done();
                      });

                    }, function(error) {
                      // NOT EXPECTED:
                      expect(false).toBe(true);
                      expect(JSON.stringify(error)).toBe('---');
                      done();
                    });

                  }, function(error) {
                    // NOT EXPECTED:
                    expect(false).toBe(true);
                    expect(JSON.stringify(error)).toBe('---');
                    done();
                  });

                }, function(error) {
                  // NOT EXPECTED:
                  expect(false).toBe(true);
                  expect(JSON.stringify(error)).toBe('---');
                  done();
                });
              });

          }, function(error) {
            // NOT EXPECTED:
            expect(false).toBe(true);
            expect(JSON.stringify(error)).toBe('---');
            done();
          });


        }, MYTIMEOUT);

    });

  }

}

if (window.hasBrowser) mytests();
else exports.defineAutoTests = mytests;

/* vim: set expandtab : */
